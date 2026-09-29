package dev.forge.auth;

import java.util.Map;
import java.util.Optional;









public interface AuthenticationProvider {


    String id();











    Optional<User> authenticate(Credentials credentials);
}
