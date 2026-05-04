package com.hah.here.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "product")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(nullable = false, precision = 12, scale = 0)
    private BigDecimal price;

    @Column(name = "check_in_at", nullable = false)
    private LocalDateTime checkInAt;

    @Column(name = "check_out_at", nullable = false)
    private LocalDateTime checkOutAt;

    @Column(name = "total_stock", nullable = false)
    private Integer totalStock;

    @Column(name = "open_at", nullable = false)
    private LocalDateTime openAt;

    @Builder
    private Product(String name, String description, BigDecimal price,
                 LocalDateTime checkInAt, LocalDateTime checkOutAt,
                 Integer totalStock, LocalDateTime openAt) {
        this.name = name;
        this.description = description;
        this.price = price;
        this.checkInAt = checkInAt;
        this.checkOutAt = checkOutAt;
        this.totalStock = totalStock;
        this.openAt = openAt;
    }

    public boolean isOpenForBooking(LocalDateTime now) {
        return !now.isBefore(openAt);
    }
}
