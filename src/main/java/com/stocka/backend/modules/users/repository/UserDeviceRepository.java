package com.stocka.backend.modules.users.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.entity.UserDevice;

@Repository
public interface UserDeviceRepository extends JpaRepository<UserDevice, Long> {

    Optional<UserDevice> findByFamilyId(String familyId);

    List<UserDevice> findByUserAndRevokedAtIsNullOrderByLastSeenAtDesc(User user);

    Optional<UserDevice> findByIdAndUser(Long id, User user);
}
