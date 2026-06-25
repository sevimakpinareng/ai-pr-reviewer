package com.example.prreviewer.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.prreviewer.entity.ReviewComment;

public interface ReviewCommentRepository extends JpaRepository<ReviewComment, Long> {

    List<ReviewComment> findByReviewId(Long reviewId);
}
