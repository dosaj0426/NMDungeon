package com.nmdungeon.command;

import com.nmdungeon.NMDungeonPlugin;
import com.nmdungeon.dungeon.DungeonService;
import com.nmdungeon.dungeon.ModuleType;
import com.nmdungeon.editor.DungeonEditSessionManager;
import com.nmdungeon.editor.DungeonEditSessionManager.DeleteResult;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class DungeonCommand implements CommandExecutor, TabCompleter {
    private final DungeonService dungeonService;
    private final DungeonEditSessionManager editor;
    private final NMDungeonPlugin plugin;

    public DungeonCommand(NMDungeonPlugin plugin, DungeonService dungeonService, DungeonEditSessionManager editor) {
        this.plugin = plugin;
        this.dungeonService = dungeonService;
        this.editor = editor;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        if (!sender.hasPermission("nmdungeon.command")) {
            send(sender, "§cYou do not have permission.");
            return true;
        }
        if (args.length == 0) {
            usage(sender);
            return true;
        }

        String[] normalized = Arrays.stream(args).map(this::normalize).toArray(String[]::new);
        switch (normalized[0].toLowerCase(Locale.ROOT)) {
            case "start" -> start(sender, normalized);
            case "end" -> end(sender, normalized);
            case "reload" -> reload(sender);
            case "delete" -> delete(sender, normalized);
            default -> {
                if (!(sender instanceof Player player)) {
                    send(sender, "§c이 명령어는 플레이어만 사용할 수 있습니다. 콘솔에서는 start, reload만 사용할 수 있습니다.");
                    return true;
                }
                switch (normalized[0].toLowerCase(Locale.ROOT)) {
                    case "clear" -> dungeonService.clearCurrentModule(player);
                    case "status" -> dungeonService.showStatus(player);
                    case "create" -> create(player, normalized);
                    case "edit" -> edit(player, normalized);
                    case "module" -> module(player, normalized);
                    case "connector" -> connector(player, normalized);
                    case "door" -> door(player, normalized);
                    case "rules" -> rules(player, normalized);
                    case "spawn" -> spawn(player, normalized);
                    case "wave" -> wave(player, normalized);
                    case "boss" -> boss(player, normalized);
                    case "validate" -> validate(player, normalized);
                    case "loadtest" -> loadTest(player, normalized);
                    default -> usage(player);
                }
            }
        }
        return true;
    }

    private void start(CommandSender sender, String[] args) {
        if (sender instanceof Player player && args.length <= 2) {
            dungeonService.start(player, args.length == 2 ? args[1] : null);
            return;
        }
        if (args.length != 3) {
            send(sender, "§e콘솔: /nmdungeon start <dungeonId> <partyPlayer>");
            send(sender, "§e플레이어: /nmdungeon start [dungeonId] 또는 /nmdungeon start <dungeonId> <partyPlayer>");
            return;
        }
        Player partyMember = Bukkit.getPlayerExact(args[2]);
        if (partyMember == null || !partyMember.isOnline()) {
            send(sender, "§c온라인 플레이어를 찾을 수 없습니다: " + args[2]);
            return;
        }
        dungeonService.startForPartyMember(partyMember, args[1]);
        send(sender, "§a[NMDungeon] " + partyMember.getName() + "님의 파티에 던전 생성 요청을 보냈습니다.");
    }

    private void end(CommandSender sender, String[] args) {
        if (sender instanceof Player player && args.length == 1) {
            dungeonService.finish(player, "관리자 명령으로 종료되었습니다.");
            return;
        }
        if (args.length != 2) {
            send(sender, "§e콘솔/관리자: /nmdungeon end <player>");
            send(sender, "§e플레이어: /nmdungeon end 또는 /nmdungeon end <player>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null || !target.isOnline()) {
            send(sender, "§c온라인 플레이어를 찾을 수 없습니다: " + args[1]);
            return;
        }
        dungeonService.finish(target, "관리자 명령으로 종료되었습니다.");
        if (!target.equals(sender)) {
            send(sender, "§a[NMDungeon] " + target.getName() + "님의 던전 종료를 요청했습니다.");
        }
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("nmdungeon.reload")) {
            send(sender, "§c리로드 권한이 없습니다.");
            return;
        }
        plugin.reloadPluginFiles();
        plugin.messages().send(sender, "system.reload-success");
    }

    private void delete(CommandSender sender, String[] args) {
        if (!sender.hasPermission("nmdungeon.delete")) {
            plugin.messages().send(sender, "command.delete-no-permission");
            return;
        }
        if (args.length != 2) {
            plugin.messages().send(sender, "command.delete-usage");
            return;
        }
        String dungeonId = args[1];
        if (dungeonService.isDungeonActive(dungeonId)) {
            plugin.messages().send(sender, "command.delete-active", "dungeon", dungeonId);
            return;
        }
        DeleteResult result = editor.deleteDungeon(dungeonId);
        switch (result) {
            case DELETED -> plugin.messages().send(sender, "command.delete-success", "dungeon", dungeonId);
            case NOT_FOUND -> plugin.messages().send(sender, "command.delete-not-found", "dungeon", dungeonId);
            case INVALID_ID -> plugin.messages().send(sender, "command.delete-invalid-id");
            case EDITING -> plugin.messages().send(sender, "command.delete-editing", "dungeon", dungeonId);
            case EDITOR_WORLD_IN_USE -> plugin.messages().send(sender, "command.delete-editor-world-in-use", "dungeon", dungeonId);
            case FAILED -> plugin.messages().send(sender, "command.delete-failed", "dungeon", dungeonId);
        }
    }

    private void loadTest(Player player, String[] args) {
        if (!player.hasPermission("nmdungeon.loadtest")) {
            send(player, "§c부하 테스트 권한이 없습니다.");
            return;
        }
        if (args.length < 2 || args.length > 3) {
            send(player, "§e/nmdungeon loadtest <dungeonId> [runs]");
            return;
        }
        int runs = args.length == 3 ? parsePositive(player, args[2], "실행 횟수") : 1;
        if (runs < 0) return;
        dungeonService.startLoadTest(player, args[1], runs);
    }

    private void create(Player player, String[] args) {
        if (args.length != 2) {
            send(player, "§e/nmdungeon create <dungeonId>");
            return;
        }
        editor.create(player, args[1]);
    }

    private void edit(Player player, String[] args) {
        if (args.length != 2) {
            send(player, "§e/nmdungeon edit <dungeonId|save|discard|exit|book>");
            return;
        }
        switch (args[1].toLowerCase()) {
            case "save" -> editor.save(player);
            case "discard" -> editor.discard(player);
            case "exit" -> editor.exit(player);
            case "book" -> editor.giveEditorBook(player);
            default -> editor.open(player, args[1]);
        }
    }

    private void module(Player player, String[] args) {
        if (args.length == 2 && args[1].equalsIgnoreCase("list")) {
            editor.listModules(player);
            return;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("export")) {
            editor.exportModule(player, args[2]);
            return;
        }
        if (args.length != 4 || !args[1].equalsIgnoreCase("create")) {
            send(player, "§e/nmdungeon module create <moduleId> <type>");
            send(player, "§e/nmdungeon module export <moduleId>");
            send(player, "§e/nmdungeon module list");
            return;
        }
        try {
            editor.addModule(player, args[2], ModuleType.valueOf(normalize(args[3]).toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException exception) {
            send(player, "§c모듈 유형: " + Arrays.toString(ModuleType.values()));
        }
    }

    private void connector(Player player, String[] args) {
        if (args.length == 3 && args[1].equalsIgnoreCase("list")) {
            editor.listConnectors(player, args[2]);
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("add") && (args.length == 4 || args.length == 6)) {
            int width = args.length == 6 ? parsePositive(player, args[4], "폭") : 5;
            int height = args.length == 6 ? parsePositive(player, args[5], "높이") : 5;
            if (width < 0 || height < 0) return;
            editor.addConnector(player, args[2], args[3], width, height);
            return;
        }
        if (args.length == 4 && (args[1].equalsIgnoreCase("remove") || args[1].equalsIgnoreCase("delete"))) {
            editor.removeConnector(player, args[2], args[3]);
            return;
        }
        if (args.length == 6 && args[1].equalsIgnoreCase("resize")) {
            int width = parsePositive(player, args[4], "폭");
            int height = parsePositive(player, args[5], "높이");
            if (width < 0 || height < 0) return;
            editor.resizeConnector(player, args[2], args[3], width, height);
            return;
        }
        send(player, "§e/nmdungeon connector add <moduleId> <connectorId> [width height]");
        send(player, "§e/nmdungeon connector resize <moduleId> <connectorId> <width> <height>");
        send(player, "§e/nmdungeon connector remove <moduleId> <connectorId>");
        send(player, "§e/nmdungeon connector list <moduleId>");
    }

    private void door(Player player, String[] args) {
        if (args.length == 3 && args[1].equalsIgnoreCase("list")) {
            editor.listDoors(player, args[2]);
            return;
        }
        if (args.length == 6 && args[1].equalsIgnoreCase("set")) {
            editor.setDoor(player, args[2], args[3], args[4], args[5]);
            return;
        }
        if (args.length == 4 && (args[1].equalsIgnoreCase("remove") || args[1].equalsIgnoreCase("delete"))) {
            editor.removeDoor(player, args[2], args[3]);
            return;
        }
        send(player, "§e/nmdungeon door set <moduleId> <doorId> <connectorId> <condition>");
        send(player, "§e/nmdungeon door remove <moduleId> <doorId>");
        send(player, "§e/nmdungeon door list <moduleId>");
    }

    private void rules(Player player, String[] args) {
        if (args.length == 2 && args[1].equalsIgnoreCase("list")) {
            editor.listGenerationRules(player);
            return;
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("set")) {
            int value = parseNonNegative(player, args[3]);
            if (value < 0) return;
            editor.setGenerationRule(player, args[2], value);
            return;
        }
        send(player, "§e/nmdungeon rules list");
        send(player, "§e/nmdungeon rules set <min-corridors|max-corridors|min-rooms|max-rooms|max-branches|max-lives|time-limit-seconds> <number>");
    }

    private void spawn(Player player, String[] args) {
        if (args.length == 4 && args[1].equalsIgnoreCase("add")) {
            editor.addSpawnPoint(player, args[2], args[3]);
            return;
        }
        send(player, "§e/nmdungeon spawn add <moduleId> <spawnPointId> §7현재 서 있는 블록을 등록합니다.");
    }

    private void wave(Player player, String[] args) {
        if ((args.length == 7 || args.length == 8) && args[1].equalsIgnoreCase("add")) {
            int amount = parsePositive(player, args[6], "수량");
            if (amount < 0) return;
            double level = args.length == 8 ? parseLevel(player, args[7]) : 1.0;
            if (level <= 0) return;
            editor.addWaveSpawn(player, args[2], args[3], args[4], args[5], amount, level);
            return;
        }
        send(player, "§e/nmdungeon wave add <moduleId> <waveId> <spawnPointId> <mythicMobId> <amount> [level]");
    }

    private void boss(Player player, String[] args) {
        if ((args.length == 5 || args.length == 6) && args[1].equalsIgnoreCase("set")) {
            double level = args.length == 6 ? parseLevel(player, args[5]) : 1.0;
            if (level <= 0) return;
            editor.setBoss(player, args[2], args[3], args[4], level);
            return;
        }
        send(player, "§e/nmdungeon boss set <moduleId> <spawnPointId> <mythicMobId> [level]");
    }

    private int parsePositive(Player player, String input, String label) {
        try {
            int value = Integer.parseInt(input);
            if (value > 0) return value;
        } catch (NumberFormatException ignored) {
        }
        send(player, "§c" + label + "은(는) 1 이상의 정수여야 합니다.");
        return -1;
    }

    private int parseNonNegative(Player player, String input) {
        try {
            int value = Integer.parseInt(input);
            if (value >= 0) return value;
        } catch (NumberFormatException ignored) {
        }
        send(player, "§c값은 0 이상의 정수여야 합니다.");
        return -1;
    }

    private double parseLevel(Player player, String input) {
        try {
            double value = Double.parseDouble(input);
            if (value > 0) return value;
        } catch (NumberFormatException ignored) {
        }
        send(player, "§c레벨은 0보다 큰 숫자여야 합니다.");
        return -1;
    }

    private void validate(Player player, String[] args) {
        List<String> results = editor.validate(player, args.length >= 2 ? args[1] : null);
        if (results.isEmpty()) {
            send(player, "§a검증 성공: 현재 던전 정의는 유효합니다.");
        } else {
            send(player, "§c검증 오류:");
            results.forEach(result -> send(player, "§7- " + result));
        }
    }

    private void usage(CommandSender sender) {
        send(sender, "§e/nmdungeon start [dungeonId] §7| ID를 생략하면 기존 MVP 테스트 맵을 시작합니다.");
        send(sender, "§e콘솔/관리자: /nmdungeon start <dungeonId> <partyPlayer>");
        send(sender, "§e/nmdungeon loadtest <dungeonId> [runs] §7| 실제 생성·삭제 성능을 측정합니다.");
        send(sender, "§e/nmdungeon <clear|status|end|create|delete|edit|module|connector|door|rules|spawn|wave|boss|validate|reload|loadtest>");
    }

    private void send(CommandSender sender, String message) {
        plugin.messages().sendText(sender, "command.chat", message);
    }

    private String normalize(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "시작" -> "start";
            case "클리어", "완료" -> "clear";
            case "상태" -> "status";
            case "종료", "끝내기" -> "end";
            case "생성" -> "create";
            case "던전삭제" -> "delete";
            case "편집" -> "edit";
            case "저장" -> "save";
            case "되돌리기", "폐기" -> "discard";
            case "나가기", "퇴장" -> "exit";
            case "모듈" -> "module";
            case "내보내기" -> "export";
            case "연결점", "커넥터" -> "connector";
            case "추가" -> "add";
            case "크기변경", "크기수정" -> "resize";
            case "삭제" -> "delete";
            case "문" -> "door";
            case "설정" -> "set";
            case "규칙", "생성규칙" -> "rules";
            case "스폰", "스폰지점" -> "spawn";
            case "웨이브" -> "wave";
            case "목록" -> "list";
            case "검증" -> "validate";
            case "리로드" -> "reload";
            case "부하테스트", "성능테스트" -> "loadtest";
            case "최소길" -> "min-corridors";
            case "최대길" -> "max-corridors";
            case "최소방" -> "min-rooms";
            case "최대방" -> "max-rooms";
            case "최대분기" -> "max-branches";
            case "목숨", "최대목숨" -> "max-lives";
            case "시간", "시간제한", "제한시간" -> "time-limit-seconds";
            case "입구" -> "entrance";
            case "길" -> "corridor";
            case "방", "전투방" -> "room";
            case "보스", "보스방" -> "boss";
            case "출구" -> "exit";
            default -> value;
        };
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                  @NotNull String alias, @NotNull String[] args) {
        boolean korean = alias.endsWith("던전");
        args = Arrays.stream(args).map(this::normalize).toArray(String[]::new);
        if (args.length == 1) {
            return suggestions(korean, "start", "clear", "status", "end", "create", "delete", "edit", "module", "connector", "door", "rules", "spawn", "wave", "boss", "validate", "reload", "loadtest");
        }
        if (args[0].equalsIgnoreCase("start") && args.length == 2) {
            return editor.dungeonIds();
        }
        if (args[0].equalsIgnoreCase("start") && args.length == 3) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        }
        if (args[0].equalsIgnoreCase("end") && args.length == 2) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        }
        if (args[0].equalsIgnoreCase("delete") && args.length == 2) {
            return editor.dungeonIds();
        }
        if (!(sender instanceof Player player)) {
            return List.of();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("edit")) {
            return suggestions(korean, "save", "discard", "exit");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("module")) {
            return suggestions(korean, "create", "export", "list");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("connector")) {
            return suggestions(korean, "add", "resize", "delete", "list");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("door")) {
            return suggestions(korean, "set", "delete", "list");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("rules")) {
            return suggestions(korean, "list", "set");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("spawn")) return suggestions(korean, "add");
        if (args.length == 2 && args[0].equalsIgnoreCase("wave")) return suggestions(korean, "add");
        if (args.length == 2 && args[0].equalsIgnoreCase("boss")) return suggestions(korean, "set");
        if (args.length == 3 && args[0].equalsIgnoreCase("spawn") && args[1].equalsIgnoreCase("add")) return editor.moduleIds(player);
        if (args.length == 3 && args[0].equalsIgnoreCase("wave") && args[1].equalsIgnoreCase("add")) return editor.moduleIds(player);
        if (args.length == 3 && args[0].equalsIgnoreCase("boss") && args[1].equalsIgnoreCase("set")) return editor.moduleIds(player);
        if (args.length == 3 && args[0].equalsIgnoreCase("rules") && args[1].equalsIgnoreCase("set")) {
            return suggestions(korean, "min-corridors", "max-corridors", "min-rooms", "max-rooms", "max-branches", "max-lives", "time-limit-seconds");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("module") && args[1].equalsIgnoreCase("export")) {
            return editor.moduleIds(player);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("connector")
                && List.of("add", "resize", "remove", "delete", "list").contains(args[1].toLowerCase())) {
            return editor.moduleIds(player);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("connector")
                && List.of("resize", "remove", "delete").contains(args[1].toLowerCase())) {
            return editor.connectorIds(player, args[2]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("door")
                && List.of("set", "remove", "delete", "list").contains(args[1].toLowerCase())) {
            return editor.moduleIds(player);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("door") && args[1].equalsIgnoreCase("remove")) {
            return editor.doorIds(player, args[2]);
        }
        if (args.length == 5 && args[0].equalsIgnoreCase("door") && args[1].equalsIgnoreCase("set")) {
            return editor.connectorIds(player, args[2]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("module") && args[1].equalsIgnoreCase("create")) {
            return korean ? List.of("입구", "길", "방", "보스", "출구")
                    : Arrays.stream(ModuleType.values()).map(Enum::name).toList();
        }
        return List.of();
    }

    private List<String> suggestions(boolean korean, String... values) {
        if (!korean) return List.of(values);
        return Arrays.stream(values).map(this::korean).toList();
    }

    private String korean(String value) {
        return switch (value) {
            case "start" -> "시작";
            case "clear" -> "클리어";
            case "status" -> "상태";
            case "end" -> "종료";
            case "create" -> "생성";
            case "edit" -> "편집";
            case "save" -> "저장";
            case "discard" -> "되돌리기";
            case "exit" -> "나가기";
            case "module" -> "모듈";
            case "export" -> "내보내기";
            case "connector" -> "연결점";
            case "add" -> "추가";
            case "resize" -> "크기변경";
            case "remove" -> "삭제";
            case "door" -> "문";
            case "set" -> "설정";
            case "rules" -> "규칙";
            case "spawn" -> "스폰";
            case "wave" -> "웨이브";
            case "list" -> "목록";
            case "validate" -> "검증";
            case "reload" -> "리로드";
            case "loadtest" -> "부하테스트";
            case "min-corridors" -> "최소길";
            case "max-corridors" -> "최대길";
            case "min-rooms" -> "최소방";
            case "max-rooms" -> "최대방";
            case "max-branches" -> "최대분기";
            case "max-lives" -> "최대목숨";
            case "time-limit-seconds" -> "시간제한";
            case "entrance" -> "입구";
            case "corridor" -> "길";
            case "room" -> "방";
            case "boss" -> "보스";
            default -> value;
        };
    }
}
