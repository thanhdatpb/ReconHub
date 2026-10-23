package vn.reconhub.api.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.reconhub.api.web.Views;
import vn.reconhub.core.domain.AppUser;
import vn.reconhub.core.repo.AppUserRepository;

import java.time.Duration;
import java.time.Instant;

@RestController
@RequestMapping("/api")
public class AuthController {
    private static final Duration TOKEN_LIFETIME = Duration.ofHours(10);

    private final AuthenticationManager auth;
    private final JwtEncoder encoder;
    private final AppUserRepository users;
    private final CurrentUser current;

    public AuthController(AuthenticationManager auth, JwtEncoder encoder, AppUserRepository users, CurrentUser current) {
        this.auth = auth;
        this.encoder = encoder;
        this.users = users;
        this.current = current;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    public record LoginResponse(String token, Instant expiresAt, Views.UserView user) {}

    @PostMapping("/auth/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        auth.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(req.username(), req.password()));
        AppUser u = users.findByUsername(req.username()).orElseThrow();
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("reconhub")
                .subject(u.getId())
                .issuedAt(now)
                .expiresAt(now.plus(TOKEN_LIFETIME))
                .claim("name", u.getFullName())
                .claim("role", u.getRole().name())
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new LoginResponse(token, claims.getExpiresAt(), Views.user(u));
    }

    @GetMapping("/me")
    public Views.UserView me() {
        return Views.user(current.get());
    }
}
