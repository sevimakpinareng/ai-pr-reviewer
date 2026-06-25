package com.example.prreviewer.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.prreviewer.entity.Review;

public interface ReviewRepository extends JpaRepository<Review, Long> {
}
