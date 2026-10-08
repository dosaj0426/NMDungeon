package com.nmdungeon.editor;

import com.nmdungeon.NMDungeonPlugin;
import com.nmdungeon.dungeon.ModuleType;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

public final class DungeonEditorMenu {
    private static final NamespacedKey ANVIL_INPUT_TOKEN = new NamespacedKey("nmdungeon", "anvil_input_token");
    public static final int MODULES = 10;
    public static final int CONNECTORS = 11;
    public static final int DOORS = 12;
    public static final int MARKERS = 13;
    public static final int VALIDATE = 15;
    public static final int SAVE = 21;
    public static final int DISCARD = 22;
    public static final int EXIT = 23;
    public static final int BACK = 49;
    public static final int ADD = 50;

    private DungeonEditorMenu() {
    }

    public static void open(Player player, DungeonEditSession session) {
        EditorMenuHolder holder = new EditorMenuHolder(session.definition().id(), Screen.DASHBOARD, null);
        Inventory inventory = Bukkit.createInventory(holder, 27, "NMDungeon Editor: " + session.definition().id());
        int connectors = session.definition().modules().values().stream().mapToInt(module -> module.connectors().size()).sum();
        int doors = session.definition().modules().values().stream().mapToInt(module -> module.doors().size()).sum();
        int unexported = (int) session.definition().modules().values().stream().filter(module -> module.templateOrigin() == null).count();
        inventory.setItem(MODULES, item(Material.CHEST, "§b모듈 관리", List.of("§7등록: §f" + session.definition().modules().size() + "개", "§7미내보냄: " + (unexported == 0 ? "§a0개" : "§c" + unexported + "개"), "§e클릭: 목록·추가·상세 편집")));
        inventory.setItem(CONNECTORS, item(Material.END_ROD, "§e연결점 관리", List.of("§7전체 연결점: §f" + connectors + "개", "§e클릭: 모듈 선택 후 연결점 관리")));
        inventory.setItem(DOORS, item(Material.OAK_DOOR, "§6문·조건 관리", List.of("§7전체 문: §f" + doors + "개", "§e클릭: 모듈 선택 후 문 관리")));
        inventory.setItem(MARKERS, item(Material.SPAWNER, "§d전투·스폰 관리", List.of("§7모듈별 스폰·웨이브·보스 설정", "§e클릭: 설정할 모듈 선택")));
        inventory.setItem(16, item(Material.CHEST_MINECART, "§6던전 완료 보상", List.of(
                "§7등록: §f" + session.definition().clearRewards().size() + "개", "§e클릭: 클리어 보상 관리")));
        inventory.setItem(14, item(Material.CLOCK, "§e생성·도전 제한", List.of(
                "§7길: §f" + session.definition().minCorridors() + "~" + session.definition().maxCorridors()
                        + " §7| 방: §f" + session.definition().minRooms() + "~" + session.definition().maxRooms(),
                "§7공유 목숨: §f" + limitText(session.definition().maxLives(), ""),
                "§7제한 시간: §f" + limitText(session.definition().timeLimitSeconds(), "초"),
                "§e클릭: 생성 규칙·목숨·시간 설정")));
        inventory.setItem(VALIDATE, item(Material.EXPERIENCE_BOTTLE, "§a전체 검증", List.of("§7오류를 모듈별로 확인하고 바로 이동합니다.")));
        inventory.setItem(SAVE, item(Material.LIME_DYE, "§a저장", List.of("§7dungeon.yml에 저장")));
        inventory.setItem(DISCARD, item(Material.WRITABLE_BOOK, "§e변경 폐기", List.of("§7마지막 저장 상태로 복원")));
        inventory.setItem(EXIT, item(Material.BARRIER, "§c편집 종료", List.of(session.dirty() ? "§e저장하지 않은 변경이 있습니다." : "§7안전하게 세션을 종료합니다.")));
        player.openInventory(inventory);
    }

    public static void openModules(Player player, DungeonEditSession session) {
        EditorMenuHolder holder = new EditorMenuHolder(session.definition().id(), Screen.MODULES, null);
        Inventory inventory = Bukkit.createInventory(holder, 54, "모듈 관리: " + session.definition().id());
        int slot = 0;
        for (ModuleDefinition module : session.definition().modules().values()) {
            if (slot >= 45) break;
            Material icon = switch (module.type()) {
                case ENTRANCE -> Material.COMPASS;
                case CORRIDOR -> Material.RAIL;
                case ROOM -> Material.CHEST;
                case BOSS -> Material.DRAGON_HEAD;
                case EXIT -> Material.ENDER_PEARL;
            };
            inventory.setItem(slot++, item(icon, "§b" + module.id(), List.of("§7유형: §f" + module.type(),
                    "§7크기: §f" + module.sizeX() + "×" + module.sizeY() + "×" + module.sizeZ(),
                    "§7가중치: §f" + module.weight(), "§7연결점: §f" + module.connectors().size() + " §7| 문: §f" + module.doors().size(),
                    "§e좌클릭: 상세 편집", "§eShift+좌클릭: 편집 원점으로 이동")));
        }
        inventory.setItem(BACK, item(Material.ARROW, "§e뒤로", List.of("§7메인 에디터로 돌아갑니다.")));
        inventory.setItem(ADD, item(Material.LIME_DYE, "§a모듈 추가", List.of("§7클릭 후 모루에서 모듈 ID를 입력합니다.")));
        player.openInventory(inventory);
    }

    public static void openModuleDetail(Player player, DungeonEditSession session, ModuleDefinition module) {
        EditorMenuHolder holder = new EditorMenuHolder(session.definition().id(), Screen.MODULE_DETAIL, module.id());
        Inventory inventory = Bukkit.createInventory(holder, 36, "모듈 상세: " + module.id());
        inventory.setItem(4, item(moduleIcon(module.type()), "§b" + module.id(), List.of(
                "§7유형: §f" + module.type(),
                "§7크기: §f" + module.sizeX() + "×" + module.sizeY() + "×" + module.sizeZ(),
                "§7생성 횟수: §f" + module.minInstances() + " ~ " + (module.maxInstances() == 0 ? "무제한" : module.maxInstances()),
                "§7schematic: " + (module.templateOrigin() == null ? "§c미내보냄" : "§a내보냄"))));
        inventory.setItem(10, item(Material.WRITABLE_BOOK, "§a현재 FAWE 선택 영역 export", List.of(
                "§7FAWE 완드로 두 모서리를 선택한 뒤 클릭", "§e선택 크기가 모듈 규격으로 저장됩니다.")));
        inventory.setItem(11, item(Material.COMPASS, "§b편집 원점으로 이동", List.of(
                module.templateOrigin() == null ? "§c먼저 export해야 합니다." : "§7이 모듈의 export 기준점으로 이동")));
        inventory.setItem(12, item(Material.RED_DYE, "§c가중치 -1", List.of("§7현재: §f" + module.weight())));
        inventory.setItem(13, item(Material.NETHER_STAR, "§e생성 가중치: §f" + module.weight(), List.of("§7높을수록 생성 후보로 더 자주 선택됩니다.")));
        inventory.setItem(14, item(Material.LIME_DYE, "§a가중치 +1", List.of("§7현재: §f" + module.weight())));
        inventory.setItem(16, item(Material.END_ROD, "§e연결점 관리", List.of("§7등록: §f" + module.connectors().size(), "§e클릭: 연결점 목록 열기")));
        inventory.setItem(17, item(Material.OAK_DOOR, "§6문 관리", List.of("§7등록: §f" + module.doors().size(), "§e클릭: 문 목록 열기")));
        inventory.setItem(18, item(Material.SPAWNER, "§d전투·스폰 관리", List.of("§7스폰: §f" + module.spawnPoints().size() + "개 · 웨이브: §f" + module.waves().size() + "개", "§e클릭: 전투 설정 화면 열기")));
        inventory.setItem(32, item(Material.CHEST_MINECART, "§6모듈 클리어 보상", List.of(
                "§7등록: §f" + module.clearRewards().size() + "개", "§e클릭: 이 모듈 완료 시 지급할 보상 설정")));
        inventory.setItem(33, item(Material.COMMAND_BLOCK, "§d모듈 클리어 명령어", List.of(
                "§7등록: §f" + module.clearCommands().size() + "개",
                "§7모든 파티원마다 콘솔에서 1회 실행됩니다.",
                "§7{player} = 대상 플레이어 닉네임", "§e클릭: 명령어 목록 관리")));
        inventory.setItem(24, item(Material.REDSTONE, "§e최소 생성 횟수: §f" + module.minInstances(), List.of("§7좌클릭: +1 · 우클릭: -1", "§7이 모듈이 우선적으로 생성될 최소 횟수")));
        inventory.setItem(25, item(Material.GOLD_INGOT, "§6최대 생성 횟수: §f" + (module.maxInstances() == 0 ? "무제한" : module.maxInstances()), List.of("§7좌클릭: +1 · 우클릭: -1", "§70은 무제한 · 최소보다 작게 설정할 수 없음")));
        int[] rotations = {0, 90, 180, 270};
        for (int index = 0; index < rotations.length; index++) {
            int rotation = rotations[index];
            boolean enabled = module.allowedRotations().contains(rotation);
            inventory.setItem(20 + index, item(enabled ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE,
                    (enabled ? "§a" : "§c") + rotation + "° 회전", List.of(enabled ? "§7허용됨 · 클릭하여 비활성화" : "§7비허용 · 클릭하여 활성화")));
        }
        inventory.setItem(28, item(Material.LODESTONE, "§b시작 지점 지정", List.of(
                pointText(module.startPoint()), "§e클릭: 현재 위치를 던전 시작 지점으로 저장", "§7입구 모듈에서 사용됩니다.")));
        inventory.setItem(29, item(Material.RECOVERY_COMPASS, "§d체크포인트 지정", List.of(
                pointText(module.checkpointPoint()), "§e클릭: 현재 위치를 사망 부활 지점으로 저장", "§7미설정 시 해당 입구의 진입 지점을 사용합니다.")));
        inventory.setItem(30, item(Material.BARRIER, "§c시작 지점 초기화", List.of("§7자동 시작 위치로 되돌립니다.")));
        inventory.setItem(31, item(Material.BARRIER, "§c체크포인트 초기화", List.of("§7입장 지점 부활로 되돌립니다.")));
        inventory.setItem(26, item(Material.ARROW, "§e뒤로", List.of("§7모듈 목록으로 돌아갑니다.")));
        player.openInventory(inventory);
    }

    public static void openConnectors(Player player, DungeonEditSession session, ModuleDefinition module) {
        EditorMenuHolder holder = new EditorMenuHolder(session.definition().id(), Screen.CONNECTORS, module.id());
        Inventory inventory = Bukkit.createInventory(holder, 54, "연결점 관리: " + module.id());
        int slot = 0;
        for (ConnectorDefinition connector : module.connectors()) {
            if (slot >= 45) break;
            inventory.setItem(slot++, item(Material.END_ROD, "§e" + connector.id(), List.of(
                    "§7방향: §f" + connector.facing(),
                    "§7위치: §f" + connector.x() + ", " + connector.y() + ", " + connector.z(),
                    "§7크기: §f" + connector.width() + "×" + connector.height(),
                    "§7분기: " + (connector.allowBranch() ? "§a허용" : "§c차단"),
                    "§e좌클릭: 상세 편집", "§c우클릭: 삭제")));
        }
        inventory.setItem(BACK, item(Material.ARROW, "§e뒤로", List.of("§7모듈 상세로 돌아갑니다.")));
        inventory.setItem(ADD, item(Material.LIME_DYE, "§a연결점 추가", List.of("§7ID를 입력한 뒤 외벽 블록을 우클릭해 지정합니다.", "§7처음 크기: 3×3")));
        player.openInventory(inventory);
    }

    public static void openConnectorDetail(Player player, DungeonEditSession session, ModuleDefinition module, ConnectorDefinition connector) {
        EditorMenuHolder holder = new EditorMenuHolder(session.definition().id(), Screen.CONNECTOR_DETAIL,
                module.id() + "\u0000" + connector.id());
        Inventory inventory = Bukkit.createInventory(holder, 27, "연결점 상세: " + connector.id());
        inventory.setItem(4, item(Material.END_ROD, "§e" + connector.id(), List.of(
                "§7방향: §f" + connector.facing(), "§7위치: §f" + connector.x() + ", " + connector.y() + ", " + connector.z(),
                "§7문: §f" + module.doors().stream().filter(door -> door.connectorId().equalsIgnoreCase(connector.id())).count(),
                pointText(connector.playerEntryPoint()))));
        inventory.setItem(10, item(Material.RED_DYE, "§c폭 -1", List.of("§7현재: §f" + connector.width())));
        inventory.setItem(11, item(Material.PAPER, "§e폭: §f" + connector.width(), List.of("§7연결점 입구의 가로 폭")));
        inventory.setItem(12, item(Material.LIME_DYE, "§a폭 +1", List.of("§7현재: §f" + connector.width())));
        inventory.setItem(14, item(Material.RED_DYE, "§c높이 -1", List.of("§7현재: §f" + connector.height())));
        inventory.setItem(15, item(Material.PAPER, "§e높이: §f" + connector.height(), List.of("§7연결점 입구의 높이")));
        inventory.setItem(16, item(Material.LIME_DYE, "§a높이 +1", List.of("§7현재: §f" + connector.height())));
        inventory.setItem(20, item(connector.allowBranch() ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE,
                connector.allowBranch() ? "§a분기 허용" : "§c분기 차단", List.of("§7클릭하여 전환합니다.")));
        inventory.setItem(18, item(Material.ENDER_PEARL, "§b플레이어 진입 지점 지정", List.of(
                pointText(connector.playerEntryPoint()), "§e클릭: 현재 위치·방향으로 지정", "§7문이 열린 뒤 파티가 이 위치로 진입합니다.")));
        inventory.setItem(19, item(Material.COMPASS, "§e진입 지점으로 이동", List.of("§7편집 월드에서 설정 위치를 확인합니다.")));
        inventory.setItem(21, item(Material.BARRIER, "§c진입 지점 초기화", List.of("§7자동 문 안쪽 위치 계산으로 되돌립니다.")));
        inventory.setItem(22, item(Material.BARRIER, "§c연결점 삭제", List.of("§c연결된 문이 있으면 삭제할 수 없습니다.", "§7클릭하여 삭제합니다.")));
        inventory.setItem(26, item(Material.ARROW, "§e뒤로", List.of("§7연결점 목록으로 돌아갑니다.")));
        player.openInventory(inventory);
    }

    public static void openDoors(Player player, DungeonEditSession session, ModuleDefinition module) {
        EditorMenuHolder holder = new EditorMenuHolder(session.definition().id(), Screen.DOORS, module.id());
        Inventory inventory = Bukkit.createInventory(holder, 54, "문 관리: " + module.id());
        int slot = 0;
        for (DoorDefinition door : module.doors()) {
            if (slot >= 45) break;
            inventory.setItem(slot++, item(Material.OAK_DOOR, "§6" + door.id(), List.of(
                    "§7연결점: §f" + door.connectorId(), "§7조건: §f" + door.conditionType(),
                    "§7영역: §f(" + door.pos1().x() + ", " + door.pos1().y() + ", " + door.pos1().z() + ") ~ ("
                            + door.pos2().x() + ", " + door.pos2().y() + ", " + door.pos2().z() + ")",
                    "§c우클릭: 삭제")));
        }
        inventory.setItem(BACK, item(Material.ARROW, "§e뒤로", List.of("§7모듈 상세로 돌아갑니다.")));
        inventory.setItem(ADD, item(Material.LIME_DYE, "§a문 추가", List.of("§7ID 입력 → 커넥터 선택 → FAWE 영역 적용", "§7조건: CLEAR_ROOM")));
        player.openInventory(inventory);
    }

    public static void openDungeonRewards(Player player, DungeonEditSession session) {
        EditorMenuHolder holder = new EditorMenuHolder(session.definition().id(), Screen.DUNGEON_REWARDS, null);
        Inventory inventory = Bukkit.createInventory(holder, 27, "던전 보상: 아이템 등록");
        List<ItemStack> rewards = session.definition().clearRewards();
        for (int index = 0; index < rewards.size() && index < 18; index++) {
            ItemStack display = rewardItem(rewards.get(index), index);
            ItemMeta meta = display.getItemMeta();
            List<String> lore = meta.getLore() == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(meta.getLore());
            lore.add("§e확률: §f" + chanceText(session.definition().clearRewardChance(index)));
            lore.add("§a좌클릭: 확률 설정");
            lore.add("§c우클릭: 보상 삭제");
            meta.setLore(lore);
            display.setItemMeta(meta);
            inventory.setItem(index, display);
        }
        inventory.setItem(22, item(Material.ARROW, "§e뒤로", List.of("§7메인 에디터로 돌아갑니다.")));
        inventory.setItem(26, item(Material.BOOK, "§b등록 방법", List.of("§7아래 인벤토리의 아이템을 좌클릭하면 보상으로 복사됩니다.", "§7등록된 보상은 좌클릭: 확률 · 우클릭: 삭제")));
        player.openInventory(inventory);
    }

    public static void openDungeonRewardChances(Player player, DungeonEditSession session, int rewardIndex) {
        List<ItemStack> rewards = session.definition().clearRewards();
        if (rewardIndex < 0 || rewardIndex >= rewards.size()) {
            openDungeonRewards(player, session);
            return;
        }
        EditorMenuHolder holder = new EditorMenuHolder(session.definition().id(), Screen.DUNGEON_REWARD_CHANCES, String.valueOf(rewardIndex));
        Inventory inventory = Bukkit.createInventory(holder, 27, "던전 보상: 확률 설정");
        ItemStack display = rewardItem(rewards.get(rewardIndex), rewardIndex);
        ItemMeta meta = display.getItemMeta();
        meta.setLore(List.of("§7현재 지급 확률", "§e" + chanceText(session.definition().clearRewardChance(rewardIndex))));
        display.setItemMeta(meta);
        inventory.setItem(4, display);
        inventory.setItem(10, item(Material.RED_DYE, "§c-10%", List.of("§7확률을 10% 낮춥니다.")));
        inventory.setItem(11, item(Material.RED_DYE, "§c-1%", List.of("§7확률을 1% 낮춥니다.")));
        inventory.setItem(13, item(Material.PAPER, "§e" + chanceText(session.definition().clearRewardChance(rewardIndex)), List.of("§7현재 지급 확률")));
        inventory.setItem(15, item(Material.LIME_DYE, "§a+1%", List.of("§7확률을 1% 높입니다.")));
        inventory.setItem(16, item(Material.LIME_DYE, "§a+10%", List.of("§7확률을 10% 높입니다.")));
        inventory.setItem(22, item(Material.ARROW, "§e보상 목록으로 돌아가기", List.of("§7확률 변경이 즉시 저장됩니다.")));
        player.openInventory(inventory);
    }

    public static void openModuleRewards(Player player, DungeonEditSession session, ModuleDefinition module) {
        EditorMenuHolder holder = new EditorMenuHolder(session.definition().id(), Screen.MODULE_REWARDS, module.id());
        Inventory inventory = Bukkit.createInventory(holder, 27, "모듈 보상: " + module.id());
        List<ItemStack> rewards = module.clearRewards();
        for (int index = 0; index < rewards.size() && index < 18; index++) {
            ItemStack display = rewardItem(rewards.get(index), index);
            ItemMeta meta = display.getItemMeta();
            List<String> lore = meta.getLore() == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(meta.getLore());
            lore.add("§e확률: §f" + chanceText(module.clearRewardChance(index)));
            lore.add("§a좌클릭: 확률 설정");
            lore.add("§c우클릭: 보상 삭제");
            meta.setLore(lore);
            display.setItemMeta(meta);
            inventory.setItem(index, display);
        }
        inventory.setItem(22, item(Material.ARROW, "§e뒤로", List.of("§7모듈 상세로 돌아갑니다.")));
        inventory.setItem(26, item(Material.BOOK, "§b등록 방법", List.of("§7아래 인벤토리의 아이템을 좌클릭하면 보상으로 복사됩니다.", "§7등록된 보상은 좌클릭: 확률 · 우클릭: 삭제")));
        player.openInventory(inventory);
    }

    public static void openModuleClearCommands(Player player, DungeonEditSession session, ModuleDefinition module) {
        EditorMenuHolder holder = new EditorMenuHolder(session.definition().id(), Screen.MODULE_CLEAR_COMMANDS, module.id());
        Inventory inventory = Bukkit.createInventory(holder, 54, "클리어 명령어: " + module.id());
        List<String> commands = module.clearCommands();
        for (int index = 0; index < commands.size() && index < 45; index++) {
            String command = commands.get(index);
            inventory.setItem(index, item(Material.COMMAND_BLOCK, "§d명령어 " + (index + 1), List.of(
                    "§f/" + command, "§7{player}는 각 파티원의 닉네임으로 치환됩니다.", "§c우클릭: 삭제")));
        }
        inventory.setItem(BACK, item(Material.ARROW, "§e뒤로", List.of("§7모듈 상세로 돌아갑니다.")));
        inventory.setItem(ADD, item(Material.LIME_DYE, "§a명령어 추가", List.of(
                "§7클릭 후 모루에서 콘솔 명령어를 입력합니다.", "§7예시: give {player} diamond 1")));
        player.openInventory(inventory);
    }

    private static void openRewards(Player player, DungeonEditSession session, String moduleId, List<ItemStack> rewards, String title) {
        Screen screen = Screen.MODULE_REWARDS;
        EditorMenuHolder holder = new EditorMenuHolder(session.definition().id(), screen, moduleId);
        Inventory inventory = Bukkit.createInventory(holder, 54, title);
        for (int index = 0; index < rewards.size() && index < 45; index++) {
            inventory.setItem(index, rewardItem(rewards.get(index), index));
        }
        inventory.setItem(BACK, item(Material.ARROW, "§e뒤로", List.of("§7이전 화면으로 돌아갑니다.")));
        inventory.setItem(ADD, item(Material.LIME_DYE, "§a손에 든 아이템 추가", List.of(
                "§7주 손에 든 아이템 전체를 보상으로 복사합니다.", "§7클리어 시 안전하게 보상으로 전달됩니다.")));
        player.openInventory(inventory);
    }

    public static void openModuleTarget(Player player, DungeonEditSession session, String target) {
        EditorMenuHolder holder = new EditorMenuHolder(session.definition().id(), Screen.MODULE_TARGET, target);
        String title = switch (target) {
            case "connectors" -> "연결점 관리할 모듈 선택";
            case "doors" -> "문 관리할 모듈 선택";
            default -> "전투·스폰 설정할 모듈 선택";
        };
        Inventory inventory = Bukkit.createInventory(holder, 54, title);
        int slot = 0;
        for (ModuleDefinition module : session.definition().modules().values()) {
            if (slot >= 45) break;
            String value = switch (target) {
                case "connectors" -> "연결점: §f" + module.connectors().size() + "개";
                case "doors" -> "문: §f" + module.doors().size() + "개";
                default -> "스폰: §f" + module.spawnPoints().size() + "개 · 웨이브: §f" + module.waves().size() + "개";
            };
            inventory.setItem(slot++, item(moduleIcon(module.type()), "§b" + module.id(), List.of("§7유형: §f" + module.type(), "§7" + value, "§e클릭하여 열기")));
        }
        inventory.setItem(BACK, item(Material.ARROW, "§e뒤로", List.of("§7메인 에디터로 돌아갑니다.")));
        player.openInventory(inventory);
    }

    public static void openValidation(Player player, DungeonEditSession session, List<String> messages) {
        EditorMenuHolder holder = new EditorMenuHolder(session.definition().id(), Screen.VALIDATION, null);
        Inventory inventory = Bukkit.createInventory(holder, 54, "던전 검증: " + session.definition().id());
        if (messages.isEmpty()) {
            inventory.setItem(22, item(Material.LIME_DYE, "§a검증 성공", List.of("§7현재 던전 정의는 실행 가능합니다.")));
        } else {
            int slot = 0;
            for (String message : messages) {
                if (slot >= 45) break;
                inventory.setItem(slot++, item(Material.RED_STAINED_GLASS_PANE, "§c검증 오류", List.of("§f" + message)));
            }
        }
        inventory.setItem(BACK, item(Material.ARROW, "§e뒤로", List.of("§7메인 에디터로 돌아갑니다.")));
        player.openInventory(inventory);
    }

    public static void openDoorConnectorPicker(Player player, DungeonEditSession session, ModuleDefinition module, String doorId) {
        EditorMenuHolder holder = new EditorMenuHolder(session.definition().id(), Screen.DOOR_CONNECTOR,
                module.id() + "\u0000" + doorId);
        Inventory inventory = Bukkit.createInventory(holder, 27, "문 연결점 선택: " + doorId);
        int slot = 0;
        for (ConnectorDefinition connector : module.connectors()) {
            if (slot >= 18) break;
            inventory.setItem(slot++, item(Material.END_ROD, "§e" + connector.id(), List.of(
                    "§7방향: §f" + connector.facing(), "§7크기: §f" + connector.width() + "×" + connector.height(),
                    "§7이 연결점에 문을 만듭니다.")));
        }
        inventory.setItem(22, item(Material.ARROW, "§e취소", List.of("§7문 목록으로 돌아갑니다.")));
        player.openInventory(inventory);
    }

    public static void openDoorSelectionApply(Player player, DungeonEditSession session, ModuleDefinition module, String doorId, String connectorId) {
        EditorMenuHolder holder = new EditorMenuHolder(session.definition().id(), Screen.DOOR_APPLY,
                module.id() + "\u0000" + doorId + "\u0000" + connectorId);
        Inventory inventory = Bukkit.createInventory(holder, 27, "문 영역 적용: " + doorId);
        inventory.setItem(11, item(Material.OAK_DOOR, "§6" + doorId, List.of(
                "§7연결점: §f" + connectorId, "§7조건: §fCLEAR_ROOM")));
        inventory.setItem(13, item(Material.LIME_DYE, "§a현재 FAWE 선택 영역 적용", List.of(
                "§7문 블록 영역의 두 모서리를 선택한 뒤 클릭", "§7영역은 연결점 크기·외벽 범위 안이어야 합니다.")));
        inventory.setItem(15, item(Material.BARRIER, "§c취소", List.of("§7문 등록을 취소하고 목록으로 돌아갑니다.")));
        player.openInventory(inventory);
    }

    public static void openModuleTypePicker(Player player, String dungeonId, String moduleId) {
        EditorMenuHolder holder = new EditorMenuHolder(dungeonId, Screen.MODULE_TYPE, moduleId);
        Inventory inventory = Bukkit.createInventory(holder, 27, "모듈 유형 선택: " + moduleId);
        ModuleType[] types = ModuleType.values();
        for (int index = 0; index < types.length; index++) {
            ModuleType type = types[index];
            Material icon = switch (type) {
                case ENTRANCE -> Material.COMPASS;
                case CORRIDOR -> Material.RAIL;
                case ROOM -> Material.CHEST;
                case BOSS -> Material.DRAGON_HEAD;
                case EXIT -> Material.ENDER_PEARL;
            };
            inventory.setItem(11 + index, item(icon, "§b" + type, List.of("§7이 유형으로 " + moduleId + " 모듈을 만듭니다.")));
        }
        inventory.setItem(22, item(Material.ARROW, "§e취소", List.of("§7모듈 목록으로 돌아갑니다.")));
        player.openInventory(inventory);
    }

    public static void openRules(Player player, DungeonEditSession session) {
        EditorMenuHolder holder = new EditorMenuHolder(session.definition().id(), Screen.RULES, null);
        Inventory inventory = Bukkit.createInventory(holder, 27, "생성 규칙: " + session.definition().id());
        inventory.setItem(10, ruleItem("§e최소 길", session.definition().minCorridors()));
        inventory.setItem(11, ruleItem("§e최대 길", session.definition().maxCorridors()));
        inventory.setItem(13, ruleItem("§b최소 방", session.definition().minRooms()));
        inventory.setItem(14, ruleItem("§b최대 방", session.definition().maxRooms()));
        inventory.setItem(16, ruleItem("§d최대 분기", session.definition().maxBranches()));
        inventory.setItem(17, item(session.definition().enforceCombatRoomCorridors() ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE,
                session.definition().enforceCombatRoomCorridors() ? "§a전투방 길 연결 규칙: 켜짐" : "§c전투방 길 연결 규칙: 꺼짐", List.of(
                "§7전투방(ROOM)은 길(CORRIDOR)에만 연결됩니다.",
                "§7길끼리 직접 연결되는 생성도 막습니다.",
                "§7활성화 시 길 수는 방 수에 맞춰 자동 증감합니다.", "§e클릭: 켜기/끄기")));
        inventory.setItem(18, item(session.definition().requireAllModulesClearedForBoss() ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE,
                session.definition().requireAllModulesClearedForBoss() ? "§a보스방 조건: 모든 모듈 클리어" : "§c보스방 조건: 즉시 입장", List.of(
                "§7켜짐: 모든 입구·길·전투방을 클리어해야", "§7보스방으로 이어지는 문이 열립니다.", "§e클릭: 켜기/끄기")));
        inventory.setItem(19, limitRuleItem(Material.TOTEM_OF_UNDYING, "§c공유 목숨 제한", session.definition().maxLives(), "", 1, 5));
        inventory.setItem(20, limitRuleItem(Material.CLOCK, "§6던전 시간 제한", session.definition().timeLimitSeconds(), "초", 60, 300));
        inventory.setItem(22, item(Material.ARROW, "§e뒤로", List.of("§7메인 에디터로 돌아갑니다.")));
        player.openInventory(inventory);
    }

    public static void openCombat(Player player, DungeonEditSession session, ModuleDefinition module) {
        EditorMenuHolder holder = new EditorMenuHolder(session.definition().id(), Screen.COMBAT, module.id());
        Inventory inventory = Bukkit.createInventory(holder, 27, "전투·스폰: " + module.id());
        inventory.setItem(10, item(Material.ENDER_PEARL, "§b스폰 지점 관리", List.of("§7등록: §f" + module.spawnPoints().size() + "개", "§e클릭: 목록·현재 위치 추가")));
        inventory.setItem(13, item(Material.SPAWNER, "§d웨이브 관리", List.of("§7등록: §f" + module.waves().size() + "개", "§e클릭: 웨이브·몬스터 설정")));
        String boss = module.boss() == null ? "§7미설정" : "§f" + module.boss().mythicMobId() + " §7Lv." + module.boss().level();
        inventory.setItem(15, item(Material.RED_DYE, "§c보스 레벨 -1", List.of("§7현재: " + boss)));
        inventory.setItem(16, item(Material.DRAGON_HEAD, "§c보스 설정", List.of("§7현재: " + boss, "§e클릭: 스폰 지점과 보스 설정")));
        inventory.setItem(17, item(Material.LIME_DYE, "§a보스 레벨 +1", List.of("§7현재: " + boss)));
        inventory.setItem(22, item(Material.ARROW, "§e뒤로", List.of("§7모듈 상세로 돌아갑니다.")));
        player.openInventory(inventory);
    }

    public static void openSpawnPoints(Player player, DungeonEditSession session, ModuleDefinition module) {
        EditorMenuHolder holder = new EditorMenuHolder(session.definition().id(), Screen.SPAWN_POINTS, module.id());
        Inventory inventory = Bukkit.createInventory(holder, 54, "스폰 지점: " + module.id());
        for (int index = 0; index < module.spawnPoints().size() && index < 45; index++) {
            SpawnPointDefinition point = module.spawnPoints().get(index);
            inventory.setItem(index, item(Material.ENDER_PEARL, "§b" + point.id(), List.of("§7위치: §f" + point.position().x() + ", " + point.position().y() + ", " + point.position().z(), "§e좌클릭: 위치로 이동", "§c우클릭: 삭제")));
        }
        inventory.setItem(BACK, item(Material.ARROW, "§e뒤로", List.of("§7전투 설정으로 돌아갑니다.")));
        inventory.setItem(ADD, item(Material.LIME_DYE, "§a현재 위치에 스폰 지점 추가", List.of("§7클릭 후 ID를 입력합니다.")));
        player.openInventory(inventory);
    }

    public static void openWaves(Player player, DungeonEditSession session, ModuleDefinition module) {
        EditorMenuHolder holder = new EditorMenuHolder(session.definition().id(), Screen.WAVES, module.id());
        Inventory inventory = Bukkit.createInventory(holder, 54, "웨이브: " + module.id());
        for (int index = 0; index < module.waves().size() && index < 45; index++) {
            WaveDefinition wave = module.waves().get(index);
            inventory.setItem(index, item(Material.SPAWNER, "§d" + wave.id(), List.of("§7몬스터 항목: §f" + wave.spawns().size() + "개", "§e좌클릭: 몬스터 설정", "§c우클릭: 웨이브 삭제")));
        }
        inventory.setItem(BACK, item(Material.ARROW, "§e뒤로", List.of("§7전투 설정으로 돌아갑니다.")));
        inventory.setItem(ADD, item(Material.LIME_DYE, "§a웨이브 추가", List.of("§7클릭 후 웨이브 ID를 입력합니다.")));
        player.openInventory(inventory);
    }

    public static void openWaveDetail(Player player, DungeonEditSession session, ModuleDefinition module, WaveDefinition wave) {
        EditorMenuHolder holder = new EditorMenuHolder(session.definition().id(), Screen.WAVE_DETAIL, module.id() + "\u0000" + wave.id());
        Inventory inventory = Bukkit.createInventory(holder, 54, "웨이브 설정: " + wave.id());
        for (int index = 0; index < wave.spawns().size() && index < 45; index++) {
            WaveSpawnDefinition spawn = wave.spawns().get(index);
            inventory.setItem(index, item(Material.ZOMBIE_HEAD, "§f" + spawn.mythicMobId(), List.of("§7스폰: §b" + spawn.spawnPointId(), "§7수량: §f" + spawn.amount() + " §7| 레벨: §f" + spawn.level(), "§e좌클릭: 수량 +1", "§eShift+좌클릭: 레벨 +1", "§e우클릭: 수량 -1", "§eShift+우클릭: 레벨 -1", "§c드롭: 삭제")));
        }
        inventory.setItem(BACK, item(Material.ARROW, "§e뒤로", List.of("§7웨이브 목록으로 돌아갑니다.")));
        inventory.setItem(ADD, item(Material.LIME_DYE, "§a몬스터 추가", List.of("§7스폰 지점을 선택한 뒤 MythicMob ID를 입력합니다.")));
        player.openInventory(inventory);
    }

    public static void openSpawnPicker(Player player, DungeonEditSession session, ModuleDefinition module, String context, boolean boss) {
        EditorMenuHolder holder = new EditorMenuHolder(session.definition().id(), boss ? Screen.BOSS_SPAWN_PICKER : Screen.WAVE_SPAWN_PICKER, context);
        Inventory inventory = Bukkit.createInventory(holder, 27, boss ? "보스 스폰 지점 선택" : "몬스터 스폰 지점 선택");
        for (int index = 0; index < module.spawnPoints().size() && index < 18; index++) {
            SpawnPointDefinition point = module.spawnPoints().get(index);
            inventory.setItem(index, item(Material.ENDER_PEARL, "§b" + point.id(), List.of("§7" + point.position().x() + ", " + point.position().y() + ", " + point.position().z(), "§e클릭: 선택")));
        }
        inventory.setItem(22, item(Material.ARROW, "§e취소", List.of("§7전투 설정으로 돌아갑니다.")));
        player.openInventory(inventory);
    }

    public static void openTextInput(Player player, String dungeonId, InputType inputType) {
        openTextInput(player, dungeonId, inputType, null);
    }

    public static void openTextInput(Player player, String dungeonId, InputType inputType, String initialText) {
        // Do not use Bukkit.createInventory(..., ANVIL): that produces a visual-only
        // CraftInventoryCustom on Paper and therefore never performs rename calculations.
        InventoryView view = player.openAnvil(null, true);
        if (view == null) {
            NMDungeonPlugin plugin = (NMDungeonPlugin) Bukkit.getPluginManager().getPlugin("NMDungeon");
            if (plugin != null) plugin.messages().sendText(player, "editor.chat", "§c앤빌 입력창을 열 수 없습니다. 다시 시도해주세요.");
            return;
        }
        String example = initialText == null ? switch (inputType) {
            case MODULE_ID -> "module_id";
            case CONNECTOR_ID -> "connector_id";
            case DOOR_ID -> "door_id";
            case SPAWN_ID -> "spawn_1";
            case WAVE_ID -> "wave_1";
            case WAVE_MOB_ID, BOSS_MOB_ID -> "MythicMobId";
            case REWARD_CHANCE, MODULE_REWARD_CHANCE -> "100";
            case MODULE_CLEAR_COMMAND -> "give {player} diamond 1";
        } : initialText;
        String title = switch (inputType) {
            case MODULE_ID -> "모듈 ID 입력";
            case CONNECTOR_ID -> "연결점 ID 입력";
            case DOOR_ID -> "문 ID 입력";
            case SPAWN_ID -> "스폰 지점 ID 입력";
            case WAVE_ID -> "웨이브 ID 입력";
            case WAVE_MOB_ID -> "웨이브 MythicMob 입력";
            case BOSS_MOB_ID -> "보스 MythicMob 입력";
            case REWARD_CHANCE -> "던전 보상 확률 입력 (0~100)";
            case MODULE_REWARD_CHANCE -> "모듈 보상 확률 입력 (0~100)";
            case MODULE_CLEAR_COMMAND -> "모듈 클리어 명령어 입력";
        };
        view.setTitle(title);
        view.setItem(0, anvilInputToken(example));
    }

    public static boolean isAnvilInputToken(ItemStack stack) {
        return stack != null && stack.hasItemMeta()
                && stack.getItemMeta().getPersistentDataContainer().has(ANVIL_INPUT_TOKEN, PersistentDataType.BYTE);
    }

    private static ItemStack anvilInputToken(String example) {
        ItemStack stack = item(Material.PAPER, example, List.of("§7이름을 변경한 뒤 결과 칸을 클릭하세요."));
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(ANVIL_INPUT_TOKEN, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemStack ruleItem(String name, int value) {
        return item(Material.REPEATER, name + " §f" + value,
                List.of("§7좌클릭: +1", "§7우클릭: -1", "§7쉬프트 클릭: ±5", "§7작은 고정 상한 없음"));
    }

    private static ItemStack limitRuleItem(Material material, String name, int value, String unit, int normalStep, int shiftStep) {
        String display = value == 0 ? "§a무제한" : "§f" + value + unit;
        return item(material, name + " §f" + display,
                List.of("§7현재: " + display, "§7좌클릭: +" + normalStep + unit, "§7우클릭: -" + normalStep + unit,
                        "§7쉬프트 클릭: ±" + shiftStep + unit, "§70으로 내리면 무제한"));
    }

    private static String limitText(int value, String unit) {
        return value == 0 ? "§a무제한" : value + unit;
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemStack rewardItem(ItemStack reward, int index) {
        ItemStack display = reward.clone();
        ItemMeta meta = display.getItemMeta();
        List<String> lore = meta.hasLore() && meta.getLore() != null ? new java.util.ArrayList<>(meta.getLore()) : new java.util.ArrayList<>();
        lore.add("§c클릭: 보상 삭제");
        lore.add("§8보상 #" + (index + 1));
        meta.setLore(lore);
        display.setItemMeta(meta);
        return display;
    }

    private static String pointText(PlayerPositionDefinition point) {
        return point == null ? "§7설정: §c자동" : "§7설정: §f" + point.x() + ", " + point.y() + ", " + point.z()
                + " §7(§f" + Math.round(point.yaw()) + "°§7)";
    }

    private static String chanceText(double chance) {
        return chance == Math.rint(chance) ? String.format(java.util.Locale.ROOT, "%.0f%%", chance)
                : String.format(java.util.Locale.ROOT, "%.1f%%", chance);
    }

    private static Material moduleIcon(ModuleType type) {
        return switch (type) {
            case ENTRANCE -> Material.COMPASS;
            case CORRIDOR -> Material.RAIL;
            case ROOM -> Material.CHEST;
            case BOSS -> Material.DRAGON_HEAD;
            case EXIT -> Material.ENDER_PEARL;
        };
    }

    public static final class EditorMenuHolder implements InventoryHolder {
        private final String dungeonId;
        private final Screen screen;
        private final String context;

        public EditorMenuHolder(String dungeonId, Screen screen, String context) {
            this.dungeonId = dungeonId;
            this.screen = screen;
            this.context = context;
        }

        public String dungeonId() {
            return dungeonId;
        }

        public Screen screen() { return screen; }
        public String context() { return context; }

        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, 27);
        }
    }

    public enum Screen { DASHBOARD, MODULES, MODULE_TYPE, MODULE_DETAIL, CONNECTORS, CONNECTOR_DETAIL, DOORS, DOOR_CONNECTOR, DOOR_APPLY, MODULE_TARGET, VALIDATION, RULES, COMBAT, SPAWN_POINTS, WAVES, WAVE_DETAIL, WAVE_SPAWN_PICKER, BOSS_SPAWN_PICKER, DUNGEON_REWARDS, DUNGEON_REWARD_CHANCES, MODULE_REWARDS, MODULE_CLEAR_COMMANDS, TEXT_INPUT }
    public enum InputType { MODULE_ID, CONNECTOR_ID, DOOR_ID, SPAWN_ID, WAVE_ID, WAVE_MOB_ID, BOSS_MOB_ID, REWARD_CHANCE, MODULE_REWARD_CHANCE, MODULE_CLEAR_COMMAND }
}
