package org.openjdk.jmc.flightrecorder.rules.jdk.general;

import static org.openjdk.jmc.common.unit.UnitLookup.NUMBER;
import static org.openjdk.jmc.common.unit.UnitLookup.NUMBER_UNITY;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.openjdk.jmc.common.IMCType;
import org.openjdk.jmc.common.item.Aggregators;
import org.openjdk.jmc.common.item.IAttribute;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IItemFilter;
import org.openjdk.jmc.common.item.IItemQuery;
import org.openjdk.jmc.common.item.ItemFilters;
import org.openjdk.jmc.common.item.ItemQueryBuilder;
import org.openjdk.jmc.common.unit.IQuantity;
import org.openjdk.jmc.common.unit.UnitLookup;
import org.openjdk.jmc.common.util.IPreferenceValueProvider;
import org.openjdk.jmc.common.util.TypedPreference;
import org.openjdk.jmc.flightrecorder.jdk.JdkAttributes;
import org.openjdk.jmc.flightrecorder.jdk.JdkFilters;
import org.openjdk.jmc.flightrecorder.jdk.JdkTypeIDs;
import org.openjdk.jmc.flightrecorder.rules.IResult;
import org.openjdk.jmc.flightrecorder.rules.IResultValueProvider;
import org.openjdk.jmc.flightrecorder.rules.IRule2;
import org.openjdk.jmc.flightrecorder.rules.ResultBuilder;
import org.openjdk.jmc.flightrecorder.rules.Severity;
import org.openjdk.jmc.flightrecorder.rules.TypedCollectionResult;
import org.openjdk.jmc.flightrecorder.rules.TypedResult;
import org.openjdk.jmc.flightrecorder.rules.jdk.messages.internal.Messages;
import org.openjdk.jmc.flightrecorder.rules.jdk.util.ClassEntry;
import org.openjdk.jmc.flightrecorder.rules.jdk.util.ColumnInfo;
import org.openjdk.jmc.flightrecorder.rules.jdk.util.IItemResultSet;
import org.openjdk.jmc.flightrecorder.rules.jdk.util.ItemResultSetException;
import org.openjdk.jmc.flightrecorder.rules.jdk.util.ItemResultSetFactory;
import org.openjdk.jmc.flightrecorder.rules.util.JfrRuleTopics;
import org.openjdk.jmc.flightrecorder.rules.util.RulesToolkit;
import org.openjdk.jmc.flightrecorder.rules.util.RulesToolkit.EventAvailability;

public class ClassLeakingRule implements IRule2 {

	private static final String RESULT_ID = "ClassLeak"; //$NON-NLS-1$
	private static final String COUNT_AGGREGATOR_ID = "count"; //$NON-NLS-1$
	
	public static final TypedPreference<IQuantity> WARNING_LIMIT = new TypedPreference<>("classLeaking.warning.limit", //$NON-NLS-1$
			Messages.getString(Messages.ClassLeakingRule_CONFIG_WARNING_LIMIT),
			Messages.getString(Messages.ClassLeakingRule_CONFIG_WARNING_LIMIT_LONG), NUMBER, NUMBER_UNITY.quantity(25));

	public static final TypedPreference<IQuantity> MAX_NUMBER_OF_CLASSES_TO_REPORT = new TypedPreference<>(
			"classLeaking.classesToReport.limit", //$NON-NLS-1$
			Messages.getString(Messages.General_CONFIG_CLASS_LIMIT),
			Messages.getString(Messages.General_CONFIG_CLASS_LIMIT_LONG), NUMBER, NUMBER_UNITY.quantity(5));

	private static final List<TypedPreference<?>> CONFIG_ATTRIBUTES = Arrays.<TypedPreference<?>> asList(WARNING_LIMIT,
			MAX_NUMBER_OF_CLASSES_TO_REPORT);
	
	public static final TypedCollectionResult<ClassEntry> LOADED_CLASSES = new TypedCollectionResult<>("loadedClasses", Messages.getString(Messages.ClassLeakingRule_RESULT_LOADED_CLASSES_NAME), Messages.getString(Messages.ClassLeakingRule_RESULT_LOADED_CLASSES_DESCRIPTION), ClassEntry.CLASS_ENTRY, ClassEntry.class); //$NON-NLS-1$
	public static final TypedResult<IMCType> MOST_LOADED_CLASS = new TypedResult<>("mostLoadedClass", Messages.getString(Messages.ClassLeakingRule_RESULT_MOST_LOADED_CLASS_NAME), Messages.getString(Messages.ClassLeakingRule_RESULT_MOST_LOADED_CLASS_DESCRIPTION), UnitLookup.CLASS, IMCType.class); //$NON-NLS-1$
	public static final TypedResult<IQuantity> MOST_LOADED_CLASS_TIMES = new TypedResult<>("mostLoadedClassTimes", Messages.getString(Messages.ClassLeakingRule_RESULT_MOST_LOADED_CLASS_LOADS_NAME), Messages.getString(Messages.ClassLeakingRule_RESULT_MOST_LOADED_CLASS_LOADS_DESCRIPTION), UnitLookup.NUMBER, IQuantity.class); //$NON-NLS-1$
	
	private static final List<TypedResult<?>> RESULT_ATTRIBUTES = Arrays.<TypedResult<?>> asList(TypedResult.SCORE, LOADED_CLASSES, MOST_LOADED_CLASS, MOST_LOADED_CLASS_TIMES);
	
	private static final Map<String, EventAvailability> REQUIRED_EVENTS = new HashMap<>();
	
	static {
		REQUIRED_EVENTS.put(JdkTypeIDs.CLASS_LOAD, EventAvailability.AVAILABLE);
		REQUIRED_EVENTS.put(JdkTypeIDs.CLASS_UNLOAD, EventAvailability.AVAILABLE);
	}
	
	@Override
	public String getId() {
		return RESULT_ID;
	}

	@Override
	public String getTopic() {
		return JfrRuleTopics.CLASS_LOADING_TOPIC;
	}

	@Override
	public String getName() {
		return Messages.getString(Messages.ClassLeakingRule_NAME);
	}

	@Override
	public Map<String, EventAvailability> getRequiredEvents() {
		return REQUIRED_EVENTS;
	}

	private IResult getResult(IItemCollection items, IPreferenceValueProvider valueProvider, IResultValueProvider dependencyResults) {
		int warningLimit = (int) valueProvider.getPreferenceValue(WARNING_LIMIT).longValue();

		ItemQueryBuilder queryLoad = ItemQueryBuilder.fromWhere(JdkFilters.CLASS_LOAD);
		queryLoad.groupBy(JdkAttributes.CLASS_LOADED);
		queryLoad.select(JdkAttributes.CLASS_LOADED);
		queryLoad.select(Aggregators.count(COUNT_AGGREGATOR_ID, "classesLoaded")); //$NON-NLS-1$
		Map<String, ClassEntry> entriesLoad = extractClassEntriesFromQuery(items, queryLoad.build());

		ItemQueryBuilder queryUnload = ItemQueryBuilder.fromWhere(ItemFilters.and(JdkFilters.CLASS_UNLOAD,
				createClassAttributeFilter(JdkAttributes.CLASS_UNLOADED, entriesLoad)));
		queryUnload.groupBy(JdkAttributes.CLASS_UNLOADED);
		queryUnload.select(JdkAttributes.CLASS_UNLOADED);
		queryUnload.select(Aggregators.count(COUNT_AGGREGATOR_ID, "classesUnloaded")); //$NON-NLS-1$
		Map<String, ClassEntry> entriesUnload = extractClassEntriesFromQuery(items, queryUnload.build());
		Map<String, ClassEntry> diff = diff(entriesLoad, entriesUnload);
		List<ClassEntry> entries = new ArrayList<>(diff.values());

		if (entries.size() > 0) {
			int classLimit = Math.min(
					(int) valueProvider.getPreferenceValue(MAX_NUMBER_OF_CLASSES_TO_REPORT).longValue(),
					entries.size());
			long maxCount = 0;
			Collections.sort(entries);
			Collection<ClassEntry> entriesOverLimit = new ArrayList<>();
			for (int i = 0; i < classLimit; i++) {
				ClassEntry entry = entries.get(i);
				entriesOverLimit.add(entry);
				maxCount = Math.max(entry.getCount().longValue(), maxCount);
			}
			double maxScore = RulesToolkit.mapExp100(maxCount, warningLimit) * 0.75;
			ClassEntry worst = entries.get(0);
			return ResultBuilder.createFor(this, valueProvider)
					.setSeverity(Severity.get(maxScore))
					.setSummary(Messages.getString(Messages.ClassLeakingRule_RESULT_SUMMARY))
					.setExplanation(Messages.getString(Messages.ClassLeakingRule_RESULT_EXPLANATION))
					.addResult(TypedResult.SCORE, UnitLookup.NUMBER_UNITY.quantity(maxScore))
					.addResult(LOADED_CLASSES, entriesOverLimit)
					.addResult(MOST_LOADED_CLASS, worst.getType())
					.addResult(MOST_LOADED_CLASS_TIMES, worst.getCount())
					.build();
		}
		return ResultBuilder.createFor(this, valueProvider)
				.setSeverity(Severity.OK)
				.setSummary(Messages.getString(Messages.ClassLeakingRule_TEXT_OK))
				.build();
	}
	
	private static IItemFilter createClassAttributeFilter(
			IAttribute<IMCType> attribute, Map<String, ClassEntry> entries) {
			List<IItemFilter> allowedClasses = new ArrayList<>();
			for (ClassEntry entry : entries.values()) {
				allowedClasses.add(ItemFilters.equals(attribute, entry.getType()));
			}
			return ItemFilters.or(allowedClasses.toArray(new IItemFilter[0]));
		}

		private Map<String, ClassEntry> diff(Map<String, ClassEntry> entriesLoad, Map<String, ClassEntry> entriesUnload) {
			// Found no corresponding unloads, so short cutting this...
			if (entriesUnload.isEmpty()) {
				return entriesLoad;
			}
			Map<String, ClassEntry> diffMap = new HashMap<>(entriesLoad.size());
			for (Entry<String, ClassEntry> mapEntryLoad : entriesLoad.entrySet()) {
				ClassEntry classEntryUnload = entriesUnload.get(mapEntryLoad.getKey());
				if (classEntryUnload != null) {
					diffMap.put(mapEntryLoad.getKey(), new ClassEntry(mapEntryLoad.getValue().getType(),
							mapEntryLoad.getValue().getCount().subtract(classEntryUnload.getCount())));
				} else {
					diffMap.put(mapEntryLoad.getKey(), mapEntryLoad.getValue());
				}
			}
			return diffMap;
		}

		private Map<String, ClassEntry> extractClassEntriesFromQuery(IItemCollection items, IItemQuery query) {
			Map<String, ClassEntry> entries = new HashMap<>();
			IItemResultSet resultSet = new ItemResultSetFactory().createResultSet(items, query);
			ColumnInfo countColumn = resultSet.getColumnMetadata().get(COUNT_AGGREGATOR_ID); // $NON-NLS-1$
			ColumnInfo classColumn = resultSet.getColumnMetadata().get(query.getGroupBy().getIdentifier());

			while (resultSet.next()) {
				IQuantity countObject;
				try {
					countObject = (IQuantity) resultSet.getValue(countColumn.getColumn());
					if (countObject != null) {
						IMCType type = (IMCType) resultSet.getValue(classColumn.getColumn());
						if (type != null) {
							ClassEntry entry = new ClassEntry(type, countObject);
							entries.put(entry.getType().getFullName(), entry);
						}
					}
				} catch (ItemResultSetException e) {
					Logger.getLogger(getClass().getName()).log(Level.WARNING, "Failed to extract class entries from query!", //$NON-NLS-1$
							e);
				}
			}
			return entries;
		}
	
	@Override
	public RunnableFuture<IResult> createEvaluation(final IItemCollection items,
			final IPreferenceValueProvider preferenceValueProvider, final IResultValueProvider dependencyResults) {
		FutureTask<IResult> evaluationTask = new FutureTask<>(new Callable<IResult>() {
			@Override
			public IResult call() throws Exception {
				return getResult(items, preferenceValueProvider, dependencyResults);
			}
		});
		return evaluationTask;
	}

	@Override
	public Collection<TypedPreference<?>> getConfigurationAttributes() {
		return CONFIG_ATTRIBUTES;
	}

	@Override
	public Collection<TypedResult<?>> getResults() {
		return RESULT_ATTRIBUTES;
	}

}
