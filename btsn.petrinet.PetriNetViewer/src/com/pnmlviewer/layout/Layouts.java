
package com.pnmlviewer.layout;

import java.awt.geom.Point2D;
import java.util.*;

import com.pnmlviewer.model.PnmlModel;

public class Layouts {

    public static void leftToRight(PnmlModel model) {
        Map<String, List<String>> out = new HashMap<>();
        Map<String, Integer> indeg = new HashMap<>();
        for (String id : model.nodes.keySet()) { out.put(id, new ArrayList<>()); indeg.put(id, 0); }
        for (PnmlModel.Arc a : model.arcs) {
            if (!model.nodes.containsKey(a.source) || !model.nodes.containsKey(a.target)) continue;
            out.get(a.source).add(a.target);
            indeg.put(a.target, indeg.get(a.target) + 1);
        }

        List<String> order = new ArrayList<>();
        Deque<String> q = new ArrayDeque<>();
        indeg.forEach((id, d) -> { if (d == 0) q.add(id); });
        Set<String> seen = new HashSet<>();
        while (!q.isEmpty()) {
            String u = q.removeFirst();
            order.add(u); seen.add(u);
            for (String v : out.get(u)) {
                indeg.put(v, indeg.get(v) - 1);
                if (indeg.get(v) == 0) q.addLast(v);
            }
        }
        for (String id : model.nodes.keySet()) if (!seen.contains(id)) order.add(id);

        Map<String, Integer> layer = new HashMap<>();
        for (String id : order) {
            int best = 0;
            for (Map.Entry<String, List<String>> e : out.entrySet())
                if (e.getValue().contains(id))
                    best = Math.max(best, layer.getOrDefault(e.getKey(), 0) + 1);
            layer.put(id, best);
        }

        int dx = 160, dy = 80;
        Map<Integer, Integer> rowPlace = new HashMap<>();
        Map<Integer, Integer> rowTrans = new HashMap<>();
        for (String id : order) {
            PnmlModel.Node n = model.nodes.get(id);
            int L = layer.getOrDefault(id, 0);
            int x = 80 + L * dx;
            int y;
            if (n.place) {
                int r = rowPlace.getOrDefault(L, 0);
                y = 100 + r * dy;
                rowPlace.put(L, r + 1);
            } else {
                int r = rowTrans.getOrDefault(L, 0);
                y = 140 + r * dy;
                rowTrans.put(L, r + 1);
            }
            n.pos = new Point2D.Double(x, y);
        }
    }
}
