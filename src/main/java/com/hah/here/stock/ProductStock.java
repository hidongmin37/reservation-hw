package com.hah.here.stock;

import com.hah.here.common.exception.BusinessException;
import com.hah.here.common.exception.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "product_stock")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductStock {

    @Id
    @Column(name = "product_id")
    private Long productId;

    @Column(nullable = false)
    private Integer remaining;

    @Builder
    private ProductStock(Long productId, Integer remaining) {
        this.productId = productId;
        this.remaining = remaining;
    }

    public void decrease() {
        if (remaining <= 0) {
            throw new BusinessException(ErrorCode.SOLD_OUT);
        }
        this.remaining--;
    }

    public void increase() {
        this.remaining++;
    }
}
