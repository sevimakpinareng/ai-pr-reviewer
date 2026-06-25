package com.example.prreviewer.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.prreviewer.entity.Installation;

public interface InstallationRepository extends JpaRepository<Installation, Long> {

    Optional<Installation> findByGithubInstallationId(Long githubInstallationId);
}
