package com.gfi.ozg.fitko.swing.model;

import dev.fitko.fitconnect.api.domain.model.event.problems.Problem;

/** A problem (rejection reason) reported in a status or event-log entry. */
public class ProblemInfo {

    public String type;
    public String title;
    public String detail;
    public String instance;

    public static ProblemInfo of(Problem problem) {
        ProblemInfo p = new ProblemInfo();
        p.type = problem.getType();
        p.title = problem.getTitle();
        p.detail = problem.getDetail();
        p.instance = problem.getInstance();
        return p;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(title == null ? type : title);
        if (detail != null && !detail.isBlank()) {
            sb.append(": ").append(detail);
        }
        if (type != null && title != null) {
            sb.append(" (").append(type).append(')');
        }
        return sb.toString();
    }
}
