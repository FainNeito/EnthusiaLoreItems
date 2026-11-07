package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import net.enthusia.loreitems.application.CrownEquipmentRecovery;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class PaperCrownInspectionCommandTest {
    @Test void permissionAndInactiveRuntimePreventReadingCustody() {
        var server = MockBukkit.mock();
        try {
            var plugin = MockBukkit.createMockPlugin();
            var player = server.addPlayer();
            var calls = new AtomicInteger();
            var recovery = reader(calls, CompletableFuture.completedFuture(Optional.empty()));
            var command = new PaperCrownInspectionCommand(plugin, recovery, () -> true);
            command.onCommand(player, null, "loreitems", new String[]{"crown"});
            assertEquals(0, calls.get());
            player.addAttachment(plugin, LoreItemsAdministrationCommandExecutor.AUDIT_PERMISSION, true);
            new PaperCrownInspectionCommand(plugin, recovery, () -> false)
                    .onCommand(player, null, "loreitems", new String[]{"crown"});
            assertEquals(0, calls.get());
            command.onCommand(player, null, "loreitems", new String[]{"crown", "force"});
            assertEquals(0, calls.get());
        } finally { MockBukkit.unmock(); }
    }

    @Test void revokedPermissionSuppressesPendingEvidenceResponse() {
        var server = MockBukkit.mock();
        try {
            var plugin = MockBukkit.createMockPlugin();
            var player = server.addPlayer();
            var attachment = player.addAttachment(plugin, LoreItemsAdministrationCommandExecutor.AUDIT_PERMISSION, true);
            var future = new CompletableFuture<Optional<CrownEquipmentRecovery.Inspection>>();
            var calls = new AtomicInteger();
            new PaperCrownInspectionCommand(plugin, reader(calls, future), () -> true)
                    .onCommand(player, null, "loreitems", new String[]{"crown"});
            assertEquals(1, calls.get());
            attachment.setPermission(LoreItemsAdministrationCommandExecutor.AUDIT_PERMISSION, false);
            future.complete(Optional.empty());
            server.getScheduler().performOneTick();
            assertNull(player.nextMessage());
        } finally { MockBukkit.unmock(); }
    }

    private CrownEquipmentRecovery reader(AtomicInteger calls, CompletionStage<Optional<CrownEquipmentRecovery.Inspection>> result) {
        return new CrownEquipmentRecovery() {
            public CompletionStage<Optional<Receipt>> findEquipped(UUID holder) {
                throw new AssertionError("Inspection must not use equipment recovery");
            }
            public CompletionStage<Optional<Inspection>> inspectCustody() {
                calls.incrementAndGet();
                return result;
            }
        };
    }
}
