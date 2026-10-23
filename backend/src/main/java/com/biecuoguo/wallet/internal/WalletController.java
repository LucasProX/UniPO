package com.biecuoguo.wallet.internal;

import com.biecuoguo.security.SecurityUtils;
import com.biecuoguo.wallet.api.WalletDtos;
import com.biecuoguo.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wallet")
public class WalletController {
    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    /** cursor 是上一页最后一条流水的 ID。 */
    @GetMapping("/me")
    public ApiResponse<WalletDtos.WalletView> me(@RequestParam(required = false) String cursor,
                                                 @RequestParam(required = false) Integer limit) {
        Long before = null;
        if (cursor != null && !cursor.isBlank()) {
            if (!cursor.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("流水游标不合法");
            before = Long.parseLong(cursor);
        }
        return ApiResponse.ok(walletService.me(SecurityUtils.currentUser(), before, limit));
    }
}
