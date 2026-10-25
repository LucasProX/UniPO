package com.biecuoguo.report.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class ReportDtos {
    private ReportDtos() {
    }

    /** category：spam / fraud / harassment / porn / impersonation / other。 */
    public record ReportRequest(@NotBlank @Size(max = 30) String category, @Size(max = 500) String detail) {}

    /** 受理回执：只告诉举报人「收到了、当前状态」，不暴露处理细节和其他举报。 */
    public record ReportReceipt(long id, String status) {}
}
