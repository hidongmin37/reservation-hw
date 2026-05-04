package com.hah.here.payment;

import com.hah.here.common.exception.BusinessException;
import com.hah.here.common.exception.ErrorCode;
import com.hah.here.payment.method.MethodType;
import lombok.experimental.UtilityClass;

import java.util.Set;

/**
 * 결제 수단 조합 정책. 신규 수단 추가 시 ALLOWED에 한 줄 추가로 끝.
 *
 * 허용되지 않는 조합:
 *   - CARD + YPAY (스펙: 신용카드와 Y페이는 혼용 불가)
 */
@UtilityClass
public class PaymentComposition {

    private final Set<Set<MethodType>> ALLOWED = Set.of(
            Set.of(MethodType.CARD),
            Set.of(MethodType.YPAY),
            Set.of(MethodType.POINT),
            Set.of(MethodType.CARD, MethodType.POINT),
            Set.of(MethodType.YPAY, MethodType.POINT)
    );

    public void validate(Set<MethodType> methods) {
        if (methods == null || methods.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PAYMENT_COMBINATION);
        }
        if (!ALLOWED.contains(methods)) {
            throw new BusinessException(ErrorCode.INVALID_PAYMENT_COMBINATION);
        }
    }
}
