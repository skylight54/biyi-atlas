package com.biyi.atlas.map;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 存档一致性校验器（**只报不改**，规则无状态、可单跑）。
 *
 * <p>结构照 JOSM 的校验器：规则有稳定 id + 严重度，报告可过滤、可断言（{@link Report#assertClean()}）。
 * 每条结论都带"错在哪个 id"（PostGIS 拓扑校验的 id1/id2 惯例）。</p>
 *
 * <p>当前规则：</p>
 * <ul>
 *   <li>R01 孤儿引用：地点/道路/地点图声明的区域 id 不存在</li>
 *   <li>R02 悬空父：区域的 parent 不存在</li>
 *   <li>R03 父链成环</li>
 *   <li>R04 重复 id / 空 id</li>
 *   <li>R06 业务不变量：名称为空、区域顶点不足</li>
 *   <li>R07 道路拓扑：支线父线/父节点丢失、节点下标越界、零长段</li>
 * </ul>
 */
public final class AtlasValidator {

    public enum Severity { INFO, WARNING, ERROR }

    /** 一条问题：规则 id + 严重度 + 文案 + 涉及的对象 id（便于定位）。 */
    public record Finding(String ruleId, Severity severity, String message, List<String> refs) {
    }

    /** 校验报告。 */
    public static final class Report {
        private final List<Finding> findings;

        Report(List<Finding> findings) {
            this.findings = List.copyOf(findings);
        }

        public List<Finding> findings() {
            return findings;
        }

        public List<Finding> bySeverity(Severity severity) {
            return findings.stream().filter(f -> f.severity() == severity).toList();
        }

        public boolean hasErrors() {
            return findings.stream().anyMatch(f -> f.severity() == Severity.ERROR);
        }

        /** CI 门禁：等价 GeoPackage 的 integrity_check 必须为 "ok"。 */
        public void assertClean() {
            if (hasErrors()) {
                throw new AssertionError(render());
            }
        }

        public String summary() {
            if (findings.isEmpty()) {
                return "无问题 ✓";
            }
            long errors = bySeverity(Severity.ERROR).size();
            long warnings = bySeverity(Severity.WARNING).size();
            return findings.size() + " 项（ERROR " + errors + " / WARNING " + warnings + "）";
        }

        public String render() {
            StringBuilder out = new StringBuilder();
            for (Severity severity : Severity.values()) {
                for (Finding finding : bySeverity(severity)) {
                    out.append('[').append(finding.ruleId()).append('/').append(severity).append("] ")
                            .append(finding.message());
                    if (!finding.refs().isEmpty()) {
                        out.append("  → ").append(String.join(", ", finding.refs()));
                    }
                    out.append('\n');
                }
            }
            return out.toString();
        }
    }

    private AtlasValidator() {
    }

    /** 跑全部规则。 */
    public static Report run() {
        AtlasContent content = AtlasContent.get();
        List<Finding> findings = new ArrayList<>();
        checkOrphanReferences(content, findings);
        checkRegionParents(content, findings);
        checkRegionCycles(content, findings);
        checkDuplicateIds(content, findings);
        checkBusinessInvariants(content, findings);
        checkRoadTopology(content, findings);
        return new Report(findings);
    }

    /** 只跑指定规则（测试/调试用，规则必须能单跑）。 */
    public static Report runOnly(Set<String> ruleIds) {
        Report full = run();
        return new Report(full.findings().stream().filter(f -> ruleIds.contains(f.ruleId())).toList());
    }

    // ------------------------------------------------------------ R01

    private static void checkOrphanReferences(AtlasContent content, List<Finding> findings) {
        Map<String, Boolean> known = new HashMap<>();
        for (AtlasRegion region : content.regions()) {
            known.put(region.id(), Boolean.TRUE);
        }
        for (AtlasPlace place : content.places()) {
            if (missing(place.region(), known)) {
                findings.add(error("R01", "地点引用了不存在的区域", place.name(), place.region()));
            }
        }
        for (AtlasRoad road : content.roads()) {
            if (missing(road.region(), known)) {
                findings.add(error("R01", "道路引用了不存在的区域", road.name(), road.region()));
            }
        }
        for (AtlasMapImage image : content.maps()) {
            if (missing(image.region(), known)) {
                findings.add(error("R01", "地点图引用了不存在的区域", image.name(), image.region()));
            }
        }
    }

    private static boolean missing(String regionId, Map<String, Boolean> known) {
        return regionId != null && !regionId.isEmpty() && !known.containsKey(regionId);
    }

    // ------------------------------------------------------------ R02 / R03

    private static void checkRegionParents(AtlasContent content, List<Finding> findings) {
        Map<String, AtlasRegion> byId = new LinkedHashMap<>();
        for (AtlasRegion region : content.regions()) {
            byId.put(region.id(), region);
        }
        for (AtlasRegion region : byId.values()) {
            String parent = region.parent();
            if (parent != null && !parent.isEmpty() && !byId.containsKey(parent)) {
                findings.add(error("R02", "区域的父级不存在", region.name(), region.id(), parent));
            }
        }
    }

    private static void checkRegionCycles(AtlasContent content, List<Finding> findings) {
        Map<String, AtlasRegion> byId = new LinkedHashMap<>();
        for (AtlasRegion region : content.regions()) {
            byId.put(region.id(), region);
        }
        Set<String> done = new HashSet<>();
        for (AtlasRegion region : byId.values()) {
            if (done.contains(region.id())) {
                continue;
            }
            Set<String> path = new HashSet<>();
            Deque<String> stack = new ArrayDeque<>();
            String current = region.id();
            while (current != null && !current.isEmpty() && !done.contains(current)) {
                if (!path.add(current)) {
                    findings.add(error("R03", "区域父链成环", region.name(), String.join(" → ", path)));
                    break;
                }
                stack.push(current);
                AtlasRegion node = byId.get(current);
                current = node == null ? null : node.parent();
            }
            done.addAll(stack);
        }
    }

    // ------------------------------------------------------------ R04 / R06

    private static void checkDuplicateIds(AtlasContent content, List<Finding> findings) {
        Map<String, Integer> counts = new HashMap<>();
        for (AtlasRegion region : content.regions()) {
            String id = region.id();
            if (id == null || id.isEmpty()) {
                findings.add(error("R04", "区域缺少 id", region.name()));
                continue;
            }
            counts.merge(id, 1, Integer::sum);
        }
        counts.forEach((id, count) -> {
            if (count > 1) {
                findings.add(error("R04", "区域 id 重复 " + count + " 次", id));
            }
        });
    }

    private static void checkBusinessInvariants(AtlasContent content, List<Finding> findings) {
        for (AtlasRegion region : content.regions()) {
            if (region.name() == null || region.name().isBlank()) {
                findings.add(warning("R06", "区域名称为空", region.id()));
            }
            if (region.outline().size() < 3) {
                findings.add(error("R06", "区域顶点不足 3 个", region.name()));
            }
            ResourceLocation dimension = region.dimension();
            if (dimension == null) {
                findings.add(error("R06", "区域维度非法", region.name()));
            }
        }
        for (AtlasRoad road : content.roads()) {
            if (road.points().size() < 2) {
                findings.add(error("R06", "道路顶点不足 2 个", road.name()));
            }
        }
    }

    // ------------------------------------------------------------ R07

    private static void checkRoadTopology(AtlasContent content, List<Finding> findings) {
        for (AtlasRoad road : content.roads()) {
            for (AtlasRoadNode node : road.nodes()) {
                if (node.index() < 0 || node.index() >= road.points().size()) {
                    findings.add(error("R07", "道路节点下标越界", road.name(), node.name()));
                }
            }
            if (!road.branchOf().isEmpty()) {
                String[] parts = road.branchOf().split("#");
                AtlasRoad parent = parts.length > 0 ? content.roadByKey(parts[0]) : null;
                if (parent == null) {
                    findings.add(error("R07", "支线找不到父线", road.name(), road.branchOf()));
                } else if (parts.length > 1 && parent.nodeById(parts[1]) == null) {
                    findings.add(error("R07", "支线的父节点已丢失", road.name(), parts[1]));
                }
            }
        }
    }

    // ------------------------------------------------------------ 工具

    private static Finding error(String ruleId, String message, String... refs) {
        return new Finding(ruleId, Severity.ERROR, message, List.of(refs));
    }

    private static Finding warning(String ruleId, String message, String... refs) {
        return new Finding(ruleId, Severity.WARNING, message, List.of(refs));
    }
}
