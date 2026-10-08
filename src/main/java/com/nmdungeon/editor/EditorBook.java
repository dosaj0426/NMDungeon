package com.nmdungeon.editor;

import com.nmdungeon.NMDungeonPlugin;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public final class EditorBook {
    private static final String VALUE = "open-editor";

    private EditorBook() {
    }

    public static ItemStack create(NMDungeonPlugin plugin) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta meta = book.getItemMeta();
        meta.setDisplayName("§b[에디터 열기]");
        meta.setLore(java.util.List.of("§7우클릭하여 NMDungeon 에디터를 엽니다."));
        meta.getPersistentDataContainer().set(key(plugin), PersistentDataType.STRING, VALUE);
        book.setItemMeta(meta);
        return book;
    }

    public static boolean isEditorBook(NMDungeonPlugin plugin, ItemStack item) {
        if (item == null || item.getType() != Material.ENCHANTED_BOOK || !item.hasItemMeta()) return false;
        return VALUE.equals(item.getItemMeta().getPersistentDataContainer().get(key(plugin), PersistentDataType.STRING));
    }

    private static NamespacedKey key(NMDungeonPlugin plugin) {
        return new NamespacedKey(plugin, "editor_book");
    }
}
