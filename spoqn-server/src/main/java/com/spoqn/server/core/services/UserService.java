package com.spoqn.server.core.services;

import java.io.UnsupportedEncodingException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.TemporalAmount;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import javax.inject.Inject;

import org.mindrot.jbcrypt.BCrypt;
import org.mybatis.guice.transactional.Transactional;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.spoqn.server.core.SpoqnContext;
import com.spoqn.server.core.exceptions.AuthenticationException;
import com.spoqn.server.core.exceptions.ExistingLoginException;
import com.spoqn.server.core.exceptions.InadequatePasswordException;
import com.spoqn.server.core.exceptions.SpoqnException;
import com.spoqn.server.data.TokenMap;
import com.spoqn.server.data.User;
import com.spoqn.server.data.access.UserDao;

import lombok.NonNull;

@Transactional
public class UserService {

    private static final int PASSWORD_MIN_LENGTH = 8;
    private static final TemporalAmount TOKEN_LIFETIME = Duration.ofMinutes(15L);

    @Inject private SpoqnContext context;
    @Inject private UserDao dao;
    
    public User getUser(UUID uuid) {
        return dao.find(uuid);
    }

    public User createUser(User user) {

        // check for existing login ID
        String loginId = user.getLoginId();
        if (dao.find(loginId) != null)
            throw new ExistingLoginException();

        // verify password requirements
        String password = user.getPassword();
        if (password.length() < PASSWORD_MIN_LENGTH)
            throw new InadequatePasswordException();

        // securely generate password hash
        String hash = BCrypt.hashpw(password, BCrypt.gensalt());

        return dao.create(user, hash);
    }

    /**
     * @return Access and refresh tokens
     * @throws AuthenticationException
     *             If authentication has failed
     */
    public TokenMap authenticate(@NonNull String loginId, @NonNull String password, @NonNull String deviceName,
            @NonNull String deviceHash) {

        if (loginId.isEmpty() || password.isEmpty())
            throw new AuthenticationException();

        // TODO check user is active
        User user = dao.find(loginId);
        if (user == null)
            throw new AuthenticationException();

        // get the stored password hash
        String hash = dao.findPassHash(user.getUuid());
        if (hash == null)
            throw new AuthenticationException();

        // verify the plaintext password against the salted hash
        boolean authenticated = BCrypt.checkpw(password, hash);
        if (!authenticated)
            throw new AuthenticationException();

        String knownDeviceName = dao.createDevice(user.getUuid(), deviceName, deviceHash);

        return TokenMap.builder()
                .access(issueAccessToken(user.getUuid()))
                .refresh(issueRefreshToken(user.getUuid(), knownDeviceName))
                .build();
    }

    /**
     * @return Refreshed access token and existing refresh token
     * @throws AuthenticationException
     *             If authentication has failed
     */
    public TokenMap refresh(@NonNull String loginId, @NonNull String refresh, @NonNull String deviceHash) {

        if (loginId.isEmpty())
            throw new AuthenticationException();

        // TODO check user is active
        User user = dao.find(loginId);
        if (user == null)
            throw new AuthenticationException();

        // verify the device hash
        String deviceName = dao.findDeviceName(user.getUuid(), deviceHash);
        if (deviceName == null)
            throw new AuthenticationException();

        // get the expected token hash for this user and device
        String hash = dao.findTokenHash(user.getUuid(), deviceName);
        if (hash == null)
            throw new AuthenticationException();

        // verify the plaintext token against the salted hash
        boolean authenticated = BCrypt.checkpw(refresh, hash);
        if (!authenticated)
            throw new AuthenticationException();

        return TokenMap.builder()
                .access(issueAccessToken(user.getUuid()))
                .refresh(refresh)
                .build();
    }

    public void revoke() {
        dao.deleteTokens(context.getUserId());
    }

    public void revoke(@NonNull String deviceName) {
        dao.deleteToken(context.getUserId(), deviceName);
    }

    private String issueAccessToken(UUID user) {

        Map<String, Object> header = Collections.singletonMap("typ", "JWT");

        Instant now = Instant.now();
        Date issued = Date.from(now);
        Date expiration = Date.from(now.plus(TOKEN_LIFETIME));

        return JWT.create()
                .withHeader(header)
                .withIssuer(issuer())
                .withSubject(user.toString())
                .withIssuedAt(issued)
                .withNotBefore(issued)
                .withExpiresAt(expiration)
                .sign(alg());
    }

    private String issueRefreshToken(UUID user, String deviceName) {

        // securely generate a token and hash it
        String token = UUID.randomUUID().toString();
        String hash = BCrypt.hashpw(token, BCrypt.gensalt());

        dao.updateToken(user, deviceName, hash);

        return token;
    }

    /**
     * @param token
     *            Session token
     * @return User ID
     * @throws AuthenticationException
     *             If the token is invalid or expired
     */
    public String resolveUserId(String token) {

        try {
            String subject = JWT.require(alg())
                    .withIssuer(issuer())
                    .build()
                    .verify(token)
                    .getSubject();

            return subject;

        } catch (JWTVerificationException e) {
            throw new AuthenticationException(e);
        }
    }

    private Algorithm alg() {
        try {
            return Algorithm.HMAC256(key());
        } catch (IllegalArgumentException | UnsupportedEncodingException e) {
            throw new SpoqnException(e);
        }
    }

    private String key() {
        // TODO read secret key from file
        return "secret";
    }

    private String issuer() {
        // TODO read issuer from file
        return "spoqn.com";
    }
}
