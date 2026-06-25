package com.example.prreviewer.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.prreviewer.entity.Repository;

/** Spring Data repository for the {@link Repository} entity (GitHub repos). */
public interface RepositoryRepository extends JpaRepository<Repository, Long> {

    Optional<Repository> findByFullName(String fullName);
}
