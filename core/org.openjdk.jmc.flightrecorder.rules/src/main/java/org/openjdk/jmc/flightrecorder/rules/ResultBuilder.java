package org.openjdk.jmc.flightrecorder.rules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import org.openjdk.jmc.common.util.IPreferenceValueProvider;
import org.openjdk.jmc.common.util.TypedPreference;

public class ResultBuilder {

	private static class Result implements IResult {

		private final Severity severity;
		private final IRule rule;
		private final String summary;
		private final String explanation;
		private final String solution;
		private final Collection<IRecordingSetting> suggestedRecordingSettings;
		private final Map<TypedResult<?>, Object> resultMap;
		private final Map<TypedResult<?>, Collection<?>> collectionResultMap;
		private final Map<TypedPreference<?>, Object> preferenceMap;

		Result(Severity severity, IRule rule, String summary, String explanation, String solution,
				Collection<IRecordingSetting> suggestedRecordingSettings, Map<TypedResult<?>, Object> resultMap,
				Map<TypedResult<?>, Collection<?>> collectionResultMap, Map<TypedPreference<?>, Object> preferenceMap) {
			this.severity = severity;
			this.rule = rule;
			this.summary = summary;
			this.explanation = explanation;
			this.solution = solution;
			this.suggestedRecordingSettings = Collections.unmodifiableCollection(suggestedRecordingSettings);
			this.resultMap = Collections.unmodifiableMap(resultMap);
			this.collectionResultMap = Collections.unmodifiableMap(collectionResultMap);
			this.preferenceMap = Collections.unmodifiableMap(preferenceMap);
		}

		@Override
		public Severity getSeverity() {
			return severity;
		}

		@Override
		public IRule getRule() {
			return rule;
		}

		@Override
		public String getSummary() {
			return summary;
		}

		@Override
		public String getExplanation() {
			return explanation;
		}

		@Override
		public String getSolution() {
			return solution;
		}

		@Override
		public Collection<IRecordingSetting> suggestRecordingSettings() {
			return suggestedRecordingSettings;
		}

		@SuppressWarnings("unchecked")
		@Override
		public <T> T getResult(TypedResult<T> key) {
			Object result = resultMap.get(key);
			if (key.getResultClass() == null) {
				return (T) result;
			}
			return key.getResultClass().cast(result);
		}

		@Override
		public <T> Collection<T> getResult(TypedCollectionResult<T> key) {
			Collection<?> collection = collectionResultMap.get(key);
			if (collection != null) {
				Collection<T> results = new ArrayList<>(collection.size());
				for (Object object : collection) {
					results.add(key.getResultClass().cast(object));
				}
				return Collections.unmodifiableCollection(results);
			}
			return Collections.emptyList();
		}

		@SuppressWarnings("unchecked")
		@Override
		public <T> T getPreference(TypedPreference<T> preference) {
			return (T) preferenceMap.get(preference);
		}
	}

	private Severity severity;
	private IRule rule;
	private String summary;
	private String explanation;
	private String solution;
	private Collection<IRecordingSetting> suggestedRecordingSettings;
	private Map<TypedResult<?>, Object> resultMap;
	private Map<TypedResult<?>, Collection<?>> collectionResultMap;
	private Map<TypedPreference<?>, Object> preferenceMap;

	public static ResultBuilder createFor(IRule rule, IPreferenceValueProvider preferenceProvider) {
		return new ResultBuilder(rule, preferenceProvider);
	}

	private ResultBuilder(IRule rule, IPreferenceValueProvider preferenceProvider) {
		this.rule = rule;
		suggestedRecordingSettings = new HashSet<>();
		resultMap = new HashMap<>();
		collectionResultMap = new HashMap<>();
		preferenceMap = new HashMap<>();
		for (TypedPreference<?> typedPreference : rule.getConfigurationAttributes()) {
			preferenceMap.put(typedPreference, preferenceProvider.getPreferenceValue(typedPreference));
		}
	}

	public ResultBuilder setSeverity(Severity severity) {
		this.severity = severity;
		return this;
	}

	public ResultBuilder setSummary(String summary) {
		this.summary = summary;
		return this;
	}

	public ResultBuilder setExplanation(String explanation) {
		this.explanation = explanation;
		return this;
	}

	public ResultBuilder setSolution(String solution) {
		this.solution = solution;
		return this;
	}

	public ResultBuilder setSuggestedRecordingSettings(Collection<IRecordingSetting> settings) {
		this.suggestedRecordingSettings = settings;
		return this;
	}

	public <T> ResultBuilder addResult(TypedCollectionResult<T> type, Collection<T> results) {
		collectionResultMap.put(type, results);
		return this;
	}

	public <T> ResultBuilder addResult(TypedResult<T> type, T result) {
		resultMap.put(type, result);
		return this;
	}

	public IResult build() {
		return new Result(severity, rule, summary, explanation, solution, suggestedRecordingSettings, resultMap,
				collectionResultMap, preferenceMap);
	}
}
