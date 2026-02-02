
package com.pnmlviewer.model;

import java.awt.geom.Point2D;
import java.util.*;

public class PnmlModel2 {

    public static class Node {
        public String id, name;
        public boolean place; // true = place, false = transition
        public int tokens = 0;
        public Point2D.Double pos = new Point2D.Double(Double.NaN, Double.NaN);
    }

    public static class Arc {
        public String id, source, target;
        public int weight = 1;
    }

    public final Map<String, Node> nodes = new LinkedHashMap<>();
    public final List<Arc> arcs = new ArrayList<>();
}
