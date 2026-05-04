package com.hah.here.point;

import com.hah.here.common.exception.BusinessException;
import com.hah.here.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class UserPointService {

    private final UserPointRepository userPointRepository;

    @Transactional(readOnly = true)
    public BigDecimal getBalance(Long userId) {
        return userPointRepository.findById(userId)
                .map(UserPoint::getBalance)
                .orElse(BigDecimal.ZERO);
    }

    @Transactional
    public void deduct(Long userId, BigDecimal amount) {
        UserPoint userPoint = userPointRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSUFFICIENT_POINTS));
        userPoint.deduct(amount);
    }

    @Transactional
    public void refund(Long userId, BigDecimal amount) {
        UserPoint userPoint = userPointRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
        userPoint.refund(amount);
    }
}
