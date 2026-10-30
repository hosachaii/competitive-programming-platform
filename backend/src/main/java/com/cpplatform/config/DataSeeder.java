package com.cpplatform.config;

import com.cpplatform.entity.Problem;
import com.cpplatform.entity.Role;
import com.cpplatform.entity.TestCase;
import com.cpplatform.entity.User;
import com.cpplatform.repository.ProblemRepository;
import com.cpplatform.repository.TestCaseRepository;
import com.cpplatform.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Seeds a dev admin account and a few sample problems so the platform is demo-ready
 * on a fresh database. Idempotent: skips anything that already exists. Disabled in
 * tests and anywhere {@code app.seed.enabled=false}.
 *
 * NOT for production — the admin password here is a well-known dev default. On a real
 * deploy either leave seeding off or change the credentials via env (see README).
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true", matchIfMissing = true)
public class DataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final ProblemRepository problemRepository;
    private final TestCaseRepository testCaseRepository;
    private final PasswordEncoder passwordEncoder;

    // Overridable for non-local use; defaults are dev-only well-known values.
    @Value("${app.seed.admin-email:admin@cp.local}")
    private String adminEmail;

    @Value("${app.seed.admin-password:admin12345}")
    private String adminPassword;

    @Override
    @Transactional
    public void run(String... args) {
        seedAdmin();
        seedProblems();
    }

    private void seedAdmin() {
        if (userRepository.existsByEmail(adminEmail)) return;
        userRepository.save(User.builder()
                .email(adminEmail)
                .username("admin")
                .passwordHash(passwordEncoder.encode(adminPassword))
                .role(Role.ADMIN)
                .build());
        log.info("Seeded dev admin: {} / {}", adminEmail, adminPassword);
    }

    private void seedProblems() {
        if (problemRepository.count() > 0) return;

        // Problem 1 — the one the smoke test exercises across all languages.
        Problem sum = problemRepository.save(Problem.builder()
                .title("Sum of Two Numbers")
                .slug("sum-of-two-numbers")
                .difficulty(Problem.Difficulty.EASY)
                .statement("Read two space-separated integers a and b from standard input and print their sum.")
                .constraints("-10^9 <= a, b <= 10^9")
                .timeLimitMs(2000)
                .memoryLimitMb(256)
                .build());
        saveCases(sum, List.of(
                tc(sum, "2 3", "5", true),
                tc(sum, "10 20", "30", true),
                tc(sum, "-5 5", "0", false),
                tc(sum, "1000000 2000000", "3000000", false)));

        // Problem 2 — list filler / variety for the demo.
        Problem reverse = problemRepository.save(Problem.builder()
                .title("Reverse a String")
                .slug("reverse-a-string")
                .difficulty(Problem.Difficulty.EASY)
                .statement("Read a single line and print it reversed.")
                .constraints("1 <= length <= 1000")
                .timeLimitMs(2000)
                .memoryLimitMb(256)
                .build());
        saveCases(reverse, List.of(
                tc(reverse, "hello", "olleh", true),
                tc(reverse, "racecar", "racecar", false)));

        // Problem 3 — list filler.
        Problem max = problemRepository.save(Problem.builder()
                .title("Maximum of Three")
                .slug("maximum-of-three")
                .difficulty(Problem.Difficulty.EASY)
                .statement("Read three space-separated integers and print the largest.")
                .constraints("-10^9 <= each value <= 10^9")
                .timeLimitMs(2000)
                .memoryLimitMb(256)
                .build());
        saveCases(max, List.of(
                tc(max, "1 2 3", "3", true),
                tc(max, "9 4 7", "9", false)));

        log.info("Seeded {} sample problems", problemRepository.count());
    }

    private TestCase tc(Problem p, String input, String expected, boolean isPublic) {
        return TestCase.builder()
                .problem(p)
                .input(input)
                .expectedOutput(expected)
                .isPublic(isPublic)
                .weight(1)
                .build();
    }

    private void saveCases(Problem p, List<TestCase> cases) {
        testCaseRepository.saveAll(cases);
    }
}
