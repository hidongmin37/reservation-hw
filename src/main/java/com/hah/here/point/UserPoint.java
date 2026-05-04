package com.hah.here.point;

import com.hah.here.common.exception.BusinessException;
import com.hah.here.common.exception.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "user_point")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserPoint {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, precision = 12, scale = 0)
    private BigDecimal balance;

    @Version
    private Long version;

    @Builder
    private UserPoint(Long userId, BigDecimal balance) {
        this.userId = userId;
        this.balance = balance;
    }

    public void deduct(BigDecimal amount) {
        if (balance.compareTo(amount) < 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_POINTS);
        }
        this.balance = this.balance.subtract(amount);
    }

    public void refund(BigDecimal amount) {
        this.balance = this.balance.add(amount);
    }
}
