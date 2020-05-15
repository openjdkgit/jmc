package org.openjdk.jmc.joverflow.ext.treemap.swt;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Item;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class TreemapItem extends Item {
	private static final String ELLIPSIS = "...";
	private static final int HORIZONTAL_PADDING = 13;
	private static final int VERTICAL_PADDING = 13;
	private static final int MIN_SIZE = 1;

	private Treemap parent;
	private TreemapItem parentItem;
	private List<TreemapItem> children = new ArrayList<>();

	private Color background = null;
	private Color foreground = null;
	private Font font = null;

	private Rectangle bounds = null;
	private Rectangle textBounds = null;
	private double realWeight = 0; // the weight of the node
	private double apparentWeight = -1; // the cached sum of all direct children's apparent weights + realWeight
	// -1 indicates not yet cached

	// the following members need to be disposed
	private Color darkenBackground = null;

	/**
	 * Constructs TreemapItem and inserts it into Treemap. Item is inserted as last direct child of the tree.
	 *
	 * @param parent a treemap control which will be the parent of the new instance (cannot be null)
	 * @param style  the style of control to construct
	 */
	public TreemapItem(Treemap parent, int style) {
		this(Treemap.checkNull(parent), parent.getRootItem(), style);
	}

	/**
	 * Constructs TreeItem and inserts it into Tree. Item is inserted as last direct child of the specified TreeItem.
	 *
	 * @param parentItem a treemap control which will be the parent of the new instance (cannot be null)
	 * @param style      the style of control to construct
	 */
	public TreemapItem(TreemapItem parentItem, int style) {
		this(checkNull(parentItem).parent, parentItem, style);
	}

	private TreemapItem(Treemap parent, TreemapItem parentItem, int style) {
		super(parent, style);

		if ((style & SWT.VIRTUAL) == SWT.VIRTUAL) {
			// TODO: implement this if we want to support SWT.VIRTUAL
			throw new UnsupportedOperationException("SWT.VIRTUAL is not support by TreemapItem");
		}

		this.parent = parent;
		this.parentItem = parentItem;

		if (parentItem != null) {
			parentItem.children.add(this); // adding a 0 weighted node to the end of decreasingly sorted list preserves 
			// the sorted structure
		}

//		parent.createItem(this);
	}

	/*package-private*/ static TreemapItem checkNull(TreemapItem item) {
		if (item == null) {
			SWT.error(SWT.ERROR_NULL_ARGUMENT);
		}

		return item;
	}

	private void sortChildren() {
		children.sort(Comparator.comparingDouble(TreemapItem::getWeight).reversed());
	}

	void updateAncestor() {
		// update apparentWeight for all ancestors
		for (TreemapItem ancestor = parentItem; ancestor != null; ancestor = ancestor.parentItem) {
			ancestor.sortChildren();
			ancestor.cacheApparentWeight();
		}
	}

	private void clearThis() {
		// TODO: clear more attributes
		this.realWeight = 0;
		this.apparentWeight = -1;
		this.foreground = null;
		this.background = null;
		this.font = null;
		
		if (darkenBackground != null && !darkenBackground.isDisposed()) {
			darkenBackground.dispose();
		}
		this.darkenBackground = null;

		this.setData(null);
		this.setText("");

		updateAncestor();
	}

	private void cacheApparentWeight() {
		double sum = 0;
		for (TreemapItem child : children) {
			sum += child.getWeight();
		}

		sum += realWeight;
		apparentWeight = sum;
	}

	/*package-private*/ void paintItem(GC gc, Rectangle bounds) {
		this.bounds = bounds;

		Color bg = gc.getBackground();
		Color fg = gc.getForeground();
		Font font = gc.getFont();

		// clear background
		gc.setBackground(getBackground());
		int[] rectangle = new int[] {bounds.x, bounds.y, //
				bounds.x + bounds.width, bounds.y, //
				bounds.x + bounds.width, bounds.y + bounds.height, //
				bounds.x, bounds.y + bounds.height};

		gc.fillPolygon(rectangle);

		if (getParent().getBordersVisible() && getParentItem() != null) {
			gc.setForeground(getDarkenBackground());
			gc.drawPolygon(rectangle);
		}

		paintTextIfPossible(gc);
		paintChildrenIfPossible(gc);

		gc.setBackground(bg);
		gc.setForeground(fg);
		gc.setFont(font);
	}

	// add label to tile if space permits
	private void paintTextIfPossible(GC gc) {
		String text = getText();
		if (text == null || text.equals("")) {
			return;
		}

		if (!tryPaintText(gc, text)) {
			tryPaintText(gc, ELLIPSIS);
		}
	}

	private boolean tryPaintText(GC gc, String text) {
		Rectangle textBounds;
		if (getParent().getBordersVisible() && getParentItem() != null) {
			textBounds = new Rectangle(bounds.x, bounds.y, bounds.width - 2, bounds.height - 2); // -2 for the border
		} else {
			textBounds = new Rectangle(bounds.x, bounds.y, bounds.width, bounds.height);
		}

		Point textExtent = gc.textExtent(text);

		if (textExtent.x > textBounds.width || textExtent.y > textBounds.height) {
			this.textBounds = null;
			return false;
		}

		textBounds.width = textExtent.x;
		textBounds.height = textExtent.y;

		gc.setFont(getFont());
		gc.setForeground(getForeground());

		if (getParent().getBordersVisible() && getParentItem() != null) {
			gc.drawText(text, bounds.x + 1, bounds.y + 1); // +1 so it doesn't overlap with the border
		} else {
			gc.drawText(text, bounds.x, bounds.y);
		}

		this.textBounds = textBounds;
		return true;
	}

	// add child tiles if space permits
	private void paintChildrenIfPossible(GC gc) {
		// calculate available sub region for child tiles
		Rectangle2D.Double availableRegion;
		{
			double w = Math.max(0, bounds.width - 2 * HORIZONTAL_PADDING);
			double h = Math.max(0, bounds.height - 2 * VERTICAL_PADDING);
			availableRegion = new Rectangle2D.Double(0, 0, w, h);
		}

		if (availableRegion.width == 0 || availableRegion.height == 0) {
			return;
		}

		// calculate child rectangles
		List<TreemapItem> elements = Arrays.asList(getItems());
		SquarifiedTreeMap algorithm = new SquarifiedTreeMap(availableRegion, elements);
		Map<TreemapItem, Rectangle2D.Double> squarifiedMap = algorithm.squarify();

		for (TreemapItem item : elements) {
			Rectangle2D.Double childRect = squarifiedMap.get(item);

			if (childRect.width < MIN_SIZE || childRect.height < MIN_SIZE) {
				continue;
			}

			Rectangle2D.Double childBounds = squarifiedMap.get(item);

			int x = (int) childBounds.x + bounds.x + HORIZONTAL_PADDING;
			int y = (int) childBounds.y + bounds.y + VERTICAL_PADDING;
			int w = (int) childBounds.width;
			int h = (int) childBounds.height;

			item.paintItem(gc, new Rectangle(x, y, w, h));
		}
	}
	
	/**
	 * Clears the item at the given zero-relative index, sorted in descending order by weight, in the receiver. The 
	 * text, weight and other attributes of the item are set to the default value.
	 * 
	 * TODO: If the tree was created with the SWT.VIRTUAL style, these attributes are requested again as needed.
	 *
	 * @param index the index of the item to clear
	 * @param all true if all child items of the indexed item should be cleared recursively, and false otherwise
	 */
	public void clear(int index, boolean all) {
		TreemapItem target = children.get(index);
		target.clearThis();

		if (all) {
			target.clearAll(true);
		}
	}

	/**
	 * Clears all the items in the receiver. The text, weight and other attributes of the items are set to their default 
	 * values. 
	 * 
	 * TODO: If the tree was created with the SWT.VIRTUAL style, these attributes are requested again as needed.
	 * 
	 * @param all true if all child items should be cleared recursively, and false otherwise
	 */
	public void clearAll(boolean all) {
		children.forEach(item -> {
			item.clearThis();
			
			if (all) {
				item.clearAll(true);
			}
		});
	}

	@Override
	public void dispose() {
		if (darkenBackground != null && !darkenBackground.isDisposed()) {
			darkenBackground.dispose();
		}

		super.dispose();
	}

	/**
	 * Returns the receiver's background color.
	 *
	 * @return the background color
	 */
	public Color getBackground() {
		if (background != null) {
			return background;
		}

		if (parentItem != null) {
			return parentItem.getBackground();
		}

		return parent.getBackground();
	}

	private Color getDarkenBackground() {
		if (darkenBackground == null || darkenBackground.isDisposed()) {
			Color bg = getBackground();
			int r = (int) (bg.getRed() * 0.8);
			int g = (int) (bg.getGreen() * 0.8);
			int b = (int) (bg.getBlue() * 0.8);

			darkenBackground = new Color(Display.getCurrent(), r, g, b);
		}
		return darkenBackground;
	}
	
	/**
	 * Sets the receiver's background color to the color specified by the argument, or to the default system color for
	 * the item if the argument is null.
	 *
	 * @param color the new color (or null)
	 */
	public void setBackground(Color color) {
		background = color;

		if (darkenBackground != null && !darkenBackground.isDisposed()) {
			darkenBackground.dispose();
		}
		darkenBackground = null;
	}

	/**
	 * Returns a rectangle describing the size and location of the receiver relative to its parent.
	 *
	 * @return the bounding rectangle of the receiver's text
	 */
	public Rectangle getBounds() {
		return bounds;
	}

	/**
	 * Returns the font that the receiver will use to paint textual information for this item.
	 *
	 * @return the receiver's font
	 */
	public Font getFont() {
		if (font != null) {
			return font;
		}
		
		if (parentItem != null) {
			return parentItem.getFont();
		}
		
		return parent.getFont();
	}

	/**
	 * Sets the font that the receiver will use to paint textual information for this item to the font specified by the
	 * argument, or to the default font for that kind of control if the argument is null.
	 *
	 * @param font the new font (or null)
	 */
	public void setFont(Font font) {
		 this.font = font;
	}

	/**
	 * Returns the foreground color that the receiver will use to draw.
	 *
	 * @return the receiver's foreground color
	 */
	public Color getForeground() {
		if (foreground != null) {
			return foreground;
		}

		if (parentItem != null) {
			return parentItem.getForeground();
		}

		return parent.getForeground();
	}

	/**
	 * Sets the foreground color at the given column index in the receiver to the color specified by the argument, or to
	 * the default system color for the item if the argument is null.
	 *
	 * @param color the new color (or null)
	 */
	public void setForeground(Color color) {
		this.foreground = color;
	}

	/**
	 * Returns the item at the given, zero-relative index, sorted in descending order by weight, in the receiver. Throws
	 * an exception if the index is out of range.
	 *
	 * @param index the index of the item to return
	 * @return the item at the given index
	 */
	public TreemapItem getItem(int index) {
		return children.get(index);
	}

	/**
	 * Returns the item at the given point in the receiver or null if no such item exists. The point is in the
	 * coordinate system of the receiver.
	 *
	 * @param point the point used to locate the item
	 * @return the item at the given point, or null if the point is not in a selectable item
	 */
	public TreemapItem getItem(Point point) {
		// TODO
		return null;
	}

	/**
	 * Returns the number of items contained in the receiver that are direct item children of the receiver.
	 *
	 * @return the number of items
	 */
	public int getItemCount() {
		return children.size();
	}

	/**
	 * Sets the number of child items contained in the receiver.
	 *
	 * @param count the number of items
	 */
	public void setItemCount(int count) {
		// TODO: implement this if we want to support SWT.VIRTUAL
		throw new UnsupportedOperationException("SWT.VIRTUAL is not support by TreemapItem");
	}

	/**
	 * Returns a (possibly empty) array of TreeItems which are the direct item children of the receiver.
	 * Note: This is not the actual structure used by the receiver to maintain its list of items, so modifying the array
	 * will not affect the receiver.
	 *
	 * @return the receiver's items
	 */
	public TreemapItem[] getItems() {
		return children.toArray(new TreemapItem[0]);
	}

	/**
	 * Returns the receiver's parent, which must be a Treemap.
	 *
	 * @return the receiver's parent
	 */
	public Treemap getParent() {
		return parent;
	}

	/**
	 * Returns the receiver's parent item, which must be a TreeItem or null when the receiver is a root.
	 *
	 * @return the receiver's parent item
	 */
	public TreemapItem getParentItem() {
		return parentItem;
	}

	/**
	 * Returns a rectangle describing the size and location relative to its parent of the text.
	 *
	 * @return the receiver's bounding text rectangle
	 */
	public Rectangle getTextBounds() {
		// TODO
		return null;
	}

	/**
	 * Returns the receiver's weight, which is the sum of weights of all its direct children. 
	 * 
	 * @return the receiver's weight
	 */
	public double getWeight() {
		if (apparentWeight == -1) {
			cacheApparentWeight();
		}

		return apparentWeight;
	}

	/**
	 * Sets the receiver's weight. Throws an exception if the receiver is not a leaf node..
	 * 
	 * @param weight the new weight
	 */
	public void setWeight(double weight) {
		if (weight < 0) {
			throw new IllegalArgumentException("weight must be positive");
		}

		realWeight = weight;
		apparentWeight = -1;

		updateAncestor();
	}

	/**
	 * Searches the receiver's list starting at the first item (index 0) until an item is found that is equal to the
	 * argument, and returns the index of that item. If no item is found, returns -1.
	 *
	 * @param item the search item
	 * @return the index of the item
	 */
	public int indexOf(TreemapItem item) {
		return children.indexOf(item);
	}

	/**
	 * Removes the item at the given, zero-relative index, sorted in descending order by weight, in the receiver. Throws
	 * an exception if the index is out of range. 
	 * 
	 * @param index index of the item to remove
	 */
	public void remove(int index) {
		parent.remove(getItem(index));
	}

	/**
	 * Removes all of the items from the receiver.
	 */
	public void removeAll() {
		for (int i = 0; i < getItemCount(); i++) {
			remove(i);
		}
	}
}