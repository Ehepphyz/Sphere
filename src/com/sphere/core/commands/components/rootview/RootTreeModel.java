package com.sphere.components.rootview;

import javax.swing.event.TreeModelListener;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shows a file's directories and objects as a tree, with an optional filter.
 *
 * Filtering keeps a directory whenever one of its objects matches, so a name
 * typed in the box does not hide the branch that holds it.
 */
public final class RootTreeModel implements TreeModel {

    private final RootNode root;
    private String filter = "";
    private final List<RootNode> visible = new ArrayList<>();

    public RootTreeModel(RootNode root) {
        this.root = root;
        rebuild();
    }

    public RootNode rootNode() {
        return root;
    }

    public void setFilter(String text) {
        this.filter = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        rebuild();
    }

    private void rebuild() {
        visible.clear();
        collect(root);
    }

    private boolean collect(RootNode node) {
        boolean keep = matches(node);
        for (RootNode child : node.children) {
            keep |= collect(child);
        }
        if (keep) {
            visible.add(node);
        }
        return keep;
    }

    private boolean matches(RootNode node) {
        if (filter.isEmpty()) {
            return true;
        }
        return node.name.toLowerCase(Locale.ROOT).contains(filter)
            || node.className.toLowerCase(Locale.ROOT).contains(filter)
            || node.title.toLowerCase(Locale.ROOT).contains(filter);
    }

    private List<RootNode> childrenOf(RootNode node) {
        List<RootNode> out = new ArrayList<>();
        for (RootNode child : node.children) {
            if (visible.contains(child)) {
                out.add(child);
            }
        }
        return out;
    }

    @Override
    public Object getRoot() {
        return root;
    }

    @Override
    public Object getChild(Object parent, int index) {
        List<RootNode> children = childrenOf((RootNode) parent);
        return index >= 0 && index < children.size() ? children.get(index) : null;
    }

    @Override
    public int getChildCount(Object parent) {
        return childrenOf((RootNode) parent).size();
    }

    @Override
    public boolean isLeaf(Object node) {
        return ((RootNode) node).children.isEmpty();
    }

    @Override
    public void valueForPathChanged(TreePath path, Object value) {
    }

    @Override
    public int getIndexOfChild(Object parent, Object child) {
        return childrenOf((RootNode) parent).indexOf(child);
    }

    @Override
    public void addTreeModelListener(TreeModelListener listener) {
    }

    @Override
    public void removeTreeModelListener(TreeModelListener listener) {
    }
}
