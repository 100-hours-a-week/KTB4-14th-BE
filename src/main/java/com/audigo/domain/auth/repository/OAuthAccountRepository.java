package com.audigo.domain.auth.repository;

import com.audigo.domain.auth.entity.OAuthAccount;
import com.audigo.domain.auth.entity.OAuthProvider;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OAuthAccountRepository extends JpaRepository<OAuthAccount, Long> {

    Optional<OAuthAccount> findByProviderAndProviderUserId(OAuthProvider provider, String providerUserId);
}
