package net.enthusia.loreitems.paper;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.enthusia.loreitems.api.v1.LoreDeliveryResult;
import net.enthusia.loreitems.api.v1.LoreDeliveryStatus;
import net.enthusia.loreitems.api.v1.LoreItemsServiceV1;
import net.enthusia.loreitems.application.DefinitionRepository;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.domain.LoreDefinition;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** One-time, short-lived creative-copy confirmation. No raw PDC UUID rewriting. */
public final class PaperCreativeCopyController {
    private static final long CONFIRMATION_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
    private static final int MAX_PENDING = 128;
    private static final String PERMISSION = GiveLoreItemCommandExecutor.GIVE_PERMISSION;

    private final Plugin plugin;
    private final Supplier<DefinitionRepository> definitions;
    private final LoreItemsServiceV1 delivery;
    private final Consumer<UUID> deliveryWakeup;
    private final PaperItemIdentityCodec codec = new PaperItemIdentityCodec();
    private final ConcurrentMap<UUID, Pending> pending = new ConcurrentHashMap<>();

    public PaperCreativeCopyController(
            Plugin plugin,
            Supplier<DefinitionRepository> definitions,
            LoreItemsServiceV1 delivery,
            Consumer<UUID> deliveryWakeup) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.deliveryWakeup = Objects.requireNonNull(deliveryWakeup, "deliveryWakeup");
    }

    public void request(Player player, ItemStack original) {
        if (!player.hasPermission(PERMISSION)) {
            player.sendMessage("Creative tracked-item duplication requires LoreItems give permission.");
            return;
        }
        if (player.getGameMode() != GameMode.CREATIVE) {
            return;
        }
        ItemIdentityReadResult result = codec.readIdentity(original);
        if (!(result instanceof ItemIdentityReadResult.Tracked tracked)) {
            player.sendMessage("Cannot duplicate a container or item with unresolved tracking data.");
            return;
        }
        pending.entrySet().removeIf(entry ->
                System.nanoTime() - entry.getValue().createdNanos() > CONFIRMATION_NANOS);
        if (pending.size() >= MAX_PENDING && !pending.containsKey(player.getUniqueId())) {
            player.sendMessage("Too many creative copy confirmations are pending. Try again.");
            return;
        }
        UUID token = UUID.randomUUID();
        pending.put(player.getUniqueId(), new Pending(token, tracked.identity(), System.nanoTime()));
        String command = "/loreitems copy confirm " + token;
        String cancel = "/loreitems copy cancel " + token;
        player.sendMessage(Component.text("Create a NEW independently tracked copy? ", NamedTextColor.AQUA)
                .append(Component.text("[YES]", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand(command)))
                .append(Component.space())
                .append(Component.text("[NO]", NamedTextColor.RED)
                        .clickEvent(ClickEvent.runCommand(cancel))));
        player.sendMessage("Copies receive a fresh instance ID and are saved before delivery. Expires in 30s.");
    }

    public boolean execute(CommandSender sender, String[] arguments) {
        if (!(sender instanceof Player player) || !sender.hasPermission(PERMISSION)) {
            sender.sendMessage("Only authorized players can confirm creative item copies.");
            return true;
        }
        if (!validAction(arguments)) {
            player.sendMessage("Usage: /loreitems copy confirm|cancel <request-id>");
            return true;
        }
        Pending request = consumeRequest(player, arguments[2]);
        if (request == null) {
            return true;
        }
        if ("cancel".equalsIgnoreCase(arguments[1])) {
            player.sendMessage("Creative copy cancelled. Original item was not changed.");
            return true;
        }
        return confirmCopy(player, request);
    }

    private static boolean validAction(String[] arguments) {
        return arguments.length == 3 && ("confirm".equalsIgnoreCase(arguments[1])
                || "cancel".equalsIgnoreCase(arguments[1]));
    }

    private Pending consumeRequest(Player player, String token) {
        UUID playerId = player.getUniqueId();
        Pending request = pending.get(playerId);
        if (request == null || !request.token().toString().equals(token)) {
            player.sendMessage("That creative copy request is missing or expired.");
            return null;
        }
        if (System.nanoTime() - request.createdNanos() > CONFIRMATION_NANOS) {
            pending.remove(playerId, request);
            player.sendMessage("That creative copy request has expired.");
            return null;
        }
        if (!pending.remove(playerId, request)) {
            player.sendMessage("That copy request was already used.");
            return null;
        }
        return request;
    }

    private boolean confirmCopy(Player player, Pending request) {
        if (player.getGameMode() != GameMode.CREATIVE
                || !stillHasSource(player, request.identity())) {
            player.sendMessage("Copy cancelled: the original tracked item is no longer accessible.");
            return true;
        }
        DefinitionRepository repository = definitions.get();
        if (repository == null) {
            player.sendMessage("LoreItems storage is not ready; no copy was created.");
            return true;
        }
        UUID playerId = player.getUniqueId();
        String operationId = "creative-copy:" + playerId + ":" + request.token();
        try {
            repository.findById(request.identity().definitionId())
                    .whenComplete((definition, failure) ->
                            resolve(playerId, operationId, definition, failure));
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Creative-copy definition lookup failed.", exception);
            player.sendMessage("Failed to look up the item definition; no copy was requested.");
        }
        return true;
    }

    private void resolve(UUID playerId, String operationId,
            Optional<LoreDefinition> definition, Throwable failure) {
        if (failure != null || definition == null || definition.isEmpty()
                || !definition.orElseThrow().active()) {
            notifyPlayer(playerId, "Original definition unavailable; no copy was created.");
            return;
        }
        queueCopy(playerId, operationId, definition.orElseThrow().key().value());
    }

    private void queueCopy(UUID playerId, String operationId, String key) {
        try {
            CompletionStage<LoreDeliveryResult> stage =
                    delivery.queueDelivery(key, playerId, operationId);
            stage.whenComplete((result, error) -> notifyDelivery(playerId, result, error));
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Creative-copy delivery failed.", exception);
            notifyPlayer(playerId, "Could not save the creative copy request.");
        }
    }

    private void notifyDelivery(UUID playerId, LoreDeliveryResult result, Throwable error) {
        if (error != null || result == null) {
            notifyPlayer(playerId, "Could not save the creative copy request.");
            return;
        }
        if (result.status() != LoreDeliveryStatus.ACCEPTED_QUEUED
                && result.status() != LoreDeliveryStatus.ALREADY_ACCEPTED) {
            notifyPlayer(playerId, "Copy was not accepted: " + result.status());
            return;
        }
        try {
            deliveryWakeup.accept(playerId);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING,
                    "Creative copy queued; immediate delivery wakeup failed.", exception);
        }
        notifyPlayer(playerId, "New tracked copy queued with its own ID. "
                + "It will arrive when you have inventory space.");
    }

    public boolean stillHasSource(Player player, LoreItemIdentity source) {
        org.bukkit.inventory.Inventory top = player.getOpenInventory().getTopInventory();
        return Arrays.stream(player.getInventory().getContents()).anyMatch(item -> matches(item, source))
                || matches(player.getItemOnCursor(), source)
                || (top != null && Arrays.stream(top.getContents())
                        .anyMatch(item -> matches(item, source)));
    }

    private boolean matches(ItemStack item, LoreItemIdentity source) {
        return item != null && !item.getType().isAir()
                && codec.readIdentity(item) instanceof ItemIdentityReadResult.Tracked tracked
                && tracked.identity().equals(source);
    }

    private void notifyPlayer(UUID id, String message) {
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                Player player = plugin.getServer().getPlayer(id);
                if (player != null) {
                    player.sendMessage(message);
                }
            });
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.FINE, "Copy confirmation notification during shutdown.", exception);
        }
    }

    UUID pendingTokenForTest(UUID playerId) {
        Pending request = pending.get(playerId);
        return request == null ? null : request.token();
    }

    public void clear() {
        pending.clear();
    }

    private record Pending(UUID token, LoreItemIdentity identity, long createdNanos) {}
}
