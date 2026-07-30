package com.company.personnelarchive.archiveimport;

import com.company.personnelarchive.common.BusinessException;
import com.company.personnelarchive.common.ErrorCode;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 单个工作簿的字段问题收集器。
 *
 * <p>动态家庭行没有业务上限，因此详细项必须有界；超过上限只报告剩余数量，避免敌对
 * 工作簿制造无界响应，同时让用户一次看到正常工作簿中的全部问题。</p>
 */
public final class WorkbookFieldIssues {
    static final int MAX_DETAILS = 100;

    private final List<WorkbookFieldIssue> details = new ArrayList<>();
    private int total;

    public void add(WorkbookFieldIssue issue) {
        total++;
        if (details.size() < MAX_DETAILS) details.add(issue);
    }

    public boolean hasIssues() {
        return total > 0;
    }

    public void throwIfAny() {
        if (!hasIssues()) return;
        String message = details.stream()
            .map(WorkbookFieldIssue::safeMessage)
            .collect(Collectors.joining("\n"));
        int omitted = total - details.size();
        if (omitted > 0) message += "\n另有 " + omitted + " 项同类问题";
        throw new BusinessException(ErrorCode.IMPORT_FIELD_VALIDATION_FAILED, message);
    }
}
