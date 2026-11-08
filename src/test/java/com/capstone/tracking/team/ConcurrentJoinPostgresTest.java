package com.capstone.tracking.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.capstone.tracking.TestSupport;
import com.capstone.tracking.support.PostgresTest;
import com.capstone.tracking.user.User;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * 12: operations at the same moment must not put more than the maximum size in a room, nor one student in two rooms,
 * nor give a room two Leaders.
 * Runs on PostgreSQL, where the row locks and partial unique indexes are real.
 */
@PostgresTest
class ConcurrentJoinPostgresTest extends TestSupport {

    @Test
    void twoStudentsEnteringAnEmptyRoomAtOnceGiveItExactlyOneLeader() throws Exception {
        World w = world(2, 0);
        UUID room = w.rooms().get(0);
        List<Integer> codes = race(
                () -> postJson("/api/v1/rooms/" + room + "/join", w.students().get(0), Map.of())
                        .andReturn().getResponse().getStatus(),
                () -> postJson("/api/v1/rooms/" + room + "/join", w.students().get(1), Map.of())
                        .andReturn().getResponse().getStatus());
        assertThat(codes).containsExactlyInAnyOrder(200, 200);
        assertThat(memberRepository.countActive(room)).isEqualTo(2);
        assertThat(memberRepository.findActive(room).stream().filter(m -> m.isLeader()).count()).isEqualTo(1);
    }

    @Test
    void twoStudentsWalkingIntoTheLastSeatLetOnlyOneIn() throws Exception {
        World w = world(6, 0);
        List<User> s = w.students();
        UUID room = createGroup(w, s.get(0), "Walk-in");
        for (int i = 1; i <= 3; i++) {
            join(room, s.get(0), s.get(i));
        }
        List<Integer> codes = race(
                () -> postJson("/api/v1/rooms/" + room + "/join", s.get(4), Map.of()).andReturn().getResponse().getStatus(),
                () -> postJson("/api/v1/rooms/" + room + "/join", s.get(5), Map.of()).andReturn().getResponse().getStatus());
        assertThat(codes).containsExactlyInAnyOrder(200, 409);
        assertThat(memberRepository.countActive(room)).isEqualTo(5);
    }

    @Test
    void twoAcceptsForTheLastSeatLetOnlyOneStudentIn() throws Exception {
        World w = world(7, 0);
        List<User> s = w.students();
        UUID group = createGroup(w, s.get(0), "Last seat");
        for (int i = 1; i <= 3; i++) {
            join(group, s.get(0), s.get(i));
        }
        UUID first = requestToJoin(group, s.get(0), s.get(4));
        UUID second = id(postJson("/api/v1/rooms/" + group + "/invitations", s.get(0), Map.of("studentId", s.get(5).getId()))
                .andExpect(status().isCreated()));

        List<Integer> codes = race(
                () -> postJson("/api/v1/requests/" + first + "/accept", s.get(0), Map.of()).andReturn().getResponse().getStatus(),
                () -> postJson("/api/v1/requests/" + second + "/accept", s.get(5), Map.of()).andReturn().getResponse().getStatus());
        assertThat(codes).containsExactlyInAnyOrder(200, 409);
        assertThat(memberRepository.countActive(group)).isEqualTo(5);
    }

    @Test
    void aStudentAcceptingTwoInvitesAtOnceJoinsOnlyOneGroup() throws Exception {
        World w = world(3, 0);
        List<User> s = w.students();
        UUID g1 = createGroup(w, s.get(0), "One");
        UUID g2 = createGroup(w, s.get(1), "Two");
        UUID i1 = id(postJson("/api/v1/rooms/" + g1 + "/invitations", s.get(0), Map.of("studentId", s.get(2).getId()))
                .andExpect(status().isCreated()));
        UUID i2 = id(postJson("/api/v1/rooms/" + g2 + "/invitations", s.get(1), Map.of("studentId", s.get(2).getId()))
                .andExpect(status().isCreated()));

        List<Integer> codes = race(
                () -> postJson("/api/v1/requests/" + i1 + "/accept", s.get(2), Map.of()).andReturn().getResponse().getStatus(),
                () -> postJson("/api/v1/requests/" + i2 + "/accept", s.get(2), Map.of()).andReturn().getResponse().getStatus());
        assertThat(codes).containsExactlyInAnyOrder(200, 409);
        assertThat(memberRepository.countActive(g1) + memberRepository.countActive(g2)).isEqualTo(3);
    }

    @SafeVarargs
    private List<Integer> race(Callable<Integer>... calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : futures) {
                codes.add(f.get());
            }
            return codes;
        } finally {
            pool.shutdownNow();
        }
    }
}
