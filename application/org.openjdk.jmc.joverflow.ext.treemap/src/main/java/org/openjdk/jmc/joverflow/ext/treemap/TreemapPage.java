package org.openjdk.jmc.joverflow.ext.treemap;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.FormAttachment;
import org.eclipse.swt.layout.FormData;
import org.eclipse.swt.layout.FormLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Item;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.part.Page;
import org.openjdk.jmc.joverflow.ext.treemap.swt.Breadcrumb;
import org.openjdk.jmc.joverflow.ext.treemap.swt.BreadcrumbItem;
import org.openjdk.jmc.joverflow.ext.treemap.swt.Treemap;
import org.openjdk.jmc.joverflow.ext.treemap.swt.TreemapItem;
import org.openjdk.jmc.joverflow.ext.treemap.swt.events.TreemapEvent;
import org.openjdk.jmc.joverflow.ext.treemap.swt.events.TreemapListener;
import org.openjdk.jmc.joverflow.heap.model.JavaClass;
import org.openjdk.jmc.joverflow.heap.model.JavaHeapObject;
import org.openjdk.jmc.joverflow.support.RefChainElement;
import org.openjdk.jmc.joverflow.ui.JOverflowEditor;
import org.openjdk.jmc.joverflow.ui.model.ModelListener;
import org.openjdk.jmc.joverflow.ui.model.ObjectCluster;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.function.Consumer;

public class TreemapPage extends Page implements ModelListener {
	private static final Color[] COLORS = {new Color(Display.getCurrent(), 250, 206, 210), // red
			new Color(Display.getCurrent(), 185, 214, 255), // blue
			new Color(Display.getCurrent(), 229, 229, 229), // grey
			new Color(Display.getCurrent(), 255, 231, 199), // orange
			new Color(Display.getCurrent(), 171, 235, 238), // aqua
			new Color(Display.getCurrent(), 228, 209, 252), // purple
			new Color(Display.getCurrent(), 255, 255, 255), // white
			new Color(Display.getCurrent(), 205, 249, 212), // green
	};
	private static final String MESSAGE_NO_INSTANCE_SELECTED = "No instances selected";
	private static final String LABEL_ROOT = "[ROOT]";

	private final JOverflowEditor editor;

	private Composite container;
	private StackLayout containerLayout;
	private Composite messageContainer;
	private Composite treemapContainer;

	private Label message;
	private Treemap treemap;
	private Breadcrumb breadcrumb;

	private HashMap<String, Double> classes = new HashMap<>();

	TreemapPage(JOverflowEditor editor) {
		this.editor = editor;
	}

	@Override
	public void createControl(Composite parent) {
		container = new Composite(parent, SWT.PUSH);
		containerLayout = new StackLayout();
		container.setLayout(containerLayout);

		messageContainer = new Composite(container, SWT.NONE);
		FillLayout layout = new FillLayout();
		layout.marginHeight = 5;
		layout.marginWidth = 5;
		messageContainer.setLayout(layout);

		message = new Label(messageContainer, SWT.NONE);
		message.setText(MESSAGE_NO_INSTANCE_SELECTED);

		treemapContainer = new Composite(container, SWT.NONE);
		treemapContainer.setLayout(new FormLayout());

		breadcrumb = new Breadcrumb(treemapContainer, SWT.NONE);
		{
			FormData bcLayoutData = new FormData();
			bcLayoutData.top = new FormAttachment(0, 0);
			bcLayoutData.left = new FormAttachment(0, 0);
			bcLayoutData.right = new FormAttachment(100, 0);
			breadcrumb.setLayoutData(bcLayoutData);
		}

		treemap = new Treemap(treemapContainer, SWT.NONE);
		{
			FormData tmLayoutData = new FormData();
			tmLayoutData.bottom = new FormAttachment(100);
			tmLayoutData.top = new FormAttachment(breadcrumb);
			tmLayoutData.left = new FormAttachment(0);
			tmLayoutData.right = new FormAttachment(100, 0);
			treemap.setLayoutData(tmLayoutData);
		}
		treemap.setText(LABEL_ROOT);

		{
			TreemapItem rootItem = treemap.getRootItem();
			BreadcrumbItem breadcrumbItem = new BreadcrumbItem(breadcrumb, SWT.NONE);
			breadcrumbItem.setData(rootItem);
			breadcrumbItem.setText(rootItem.getText());
		}

		{
			breadcrumb.addSelectionListener(SelectionListener.widgetSelectedAdapter(selectionEvent -> {
				if (!(selectionEvent.data instanceof TreemapItem)) {
					return;
				}

				TreemapItem item = (TreemapItem) selectionEvent.data;
				treemap.setTopItem(item);
			}));

			treemap.addTreemapListener(TreemapListener.treemapTopChangedAdapter(treemapEvent -> {
				TreemapItem item = (TreemapItem) treemapEvent.item;
				breadcrumb.removeAll();

				List<TreemapItem> path = new ArrayList<>();
				do {
					path.add(item);
					item = item.getParentItem();
				} while (item != null);

				Collections.reverse(path);
				for (TreemapItem i : path) {
					BreadcrumbItem breadcrumbItem = new BreadcrumbItem(breadcrumb, SWT.NONE);
					breadcrumbItem.setData(i);
					breadcrumbItem.setText(i.getText());
				}
			}));
		}

		containerLayout.topControl = messageContainer;
		updateInput();
	}

	@Override
	public Control getControl() {
		return container;
	}

	@Override
	public void setFocus() {
		treemap.setFocus();
	}

	@Override
	public void include(
			ObjectCluster cluster, RefChainElement referenceChain) {
		if (cluster.getObjectCount() == 0) {
			return;
		}

		JavaClass clazz = getObjectAtPosition(cluster.getGlobalObjectIndex(0)).getClazz();
		String className = clazz.getName();
		if (className.charAt(0) == '[') {
			className = cluster.getClassName();
		}

		classes.putIfAbsent(className, 0.0);
		double size = classes.get(className);
		size += cluster.getMemory();
		classes.put(className, size);
	}

	@Override
	public void allIncluded() {
		updateInput();
		classes.clear();
	}

	private void updateInput() {
		if (classes.size() == 0) {
			containerLayout.topControl = messageContainer;
			container.layout();
			return;
		}

		if (treemap == null) {
			return;
		}

		treemap.removeAll();
		HashMap<String, TreemapItem> items = new HashMap<>();
		for (Map.Entry<String, Double> entry : classes.entrySet()) {
			addTreemapItem(treemap, items, entry.getKey(), entry.getValue());
		}

		TreemapItem rootItem = treemap.getRootItem();
		rootItem.setMessage(LABEL_ROOT);
		setColorAndMessage(rootItem, 0);
		treemap.setTopItem(rootItem);
		treemap.setSelection(null);

		containerLayout.topControl = treemapContainer;
		container.layout();
	}

	private void addTreemapItem(Treemap parent, Map<String, TreemapItem> items, String fullName, double size) {
		if (items.containsKey(fullName) && size != 0) {
			TreemapItem item = items.get(fullName);
			double bytes = item.getWeight() + size;
			item.setWeight(bytes);
			item.setMessage(fullName);
			return;
		}

		if (fullName.indexOf('.') == -1) {
			TreemapItem item = new TreemapItem(parent, SWT.NONE);
			item.setText(fullName);
			if (size != 0) {
				item.setWeight(size);
			}
			item.setMessage(fullName);
			items.put(fullName, item);
			return;
		}

		String parentName = fullName.substring(0, fullName.lastIndexOf('.'));
		if (!items.containsKey(parentName)) {
			addTreemapItem(parent, items, parentName, 0);
		}

		TreemapItem parentItem = items.get(parentName);
		TreemapItem item = new TreemapItem(parentItem, SWT.NONE);
		item.setText(fullName.substring(parentName.length() + 1));
		item.setMessage(fullName);
		if (size != 0) {
			item.setWeight(size);
		}
		items.put(fullName, item);
	}

	private void setColorAndMessage(TreemapItem item, int depth) {
		item.setMessage(item.getMessage() + "\n" + getHumanReadableSize(item.getWeight()));
		item.setBackground(COLORS[depth % COLORS.length]);

		for (TreemapItem child : item.getItems()) {
			setColorAndMessage(child, depth + 1);
		}
	}

	private String getHumanReadableSize(double bytes) {
		String unit = "B";
		double quantity = bytes;
		if (quantity > 1024) {
			quantity /= 1024;
			unit = "KiB";
		}
		if (quantity > 1024) {
			quantity /= 1024;
			unit = "MiB";
		}
		if (quantity > 1024) {
			quantity /= 1024;
			unit = "GiB";
		}
		if (quantity > 1024) {
			quantity /= 1024;
			unit = "TiB";
		}

		return String.format("%.2f %s", quantity, unit);
	}

	private JavaHeapObject getObjectAtPosition(int globalObjectPos) {
		return editor.getSnapshot().getObjectAtGlobalIndex(globalObjectPos);
	}
}
