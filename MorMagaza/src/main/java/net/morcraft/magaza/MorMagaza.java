package net.morcraft.magaza;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.InheritanceNode;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class MorMagaza extends JavaPlugin implements Listener {
    private Economy economy;
    private LuckPerms luckPerms;
    private final String title = ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "MORCRAFT • RÜTBE MAĞAZASI";
    private final String[] ranks = {"vip", "vipplus", "mvp"};
    private final String[] displayNames = {"VIP", "VIP+", "MVP"};
    private final int[] prices = {30000, 60000, 100000};
    private final Material[] icons = {Material.EMERALD, Material.DIAMOND, Material.NETHER_STAR};

    @Override
    public void onEnable() {
        saveDefaultConfig();
        RegisteredServiceProvider<Economy> registration =
                getServer().getServicesManager().getRegistration(Economy.class);
        if (registration == null) {
            getLogger().severe("Vault ekonomi sağlayıcısı bulunamadı. EssentialsX Economy etkin mi?");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        economy = registration.getProvider();
        try {
            luckPerms = LuckPermsProvider.get();
        } catch (IllegalStateException ex) {
            getLogger().severe("LuckPerms API bulunamadı.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("MorMagaza etkin. /magaza");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Bu komut sadece oyuncular tarafından kullanılabilir.");
            return true;
        }
        if (!player.hasPermission("mormagaza.use")) {
            player.sendMessage(ChatColor.RED + "Bu mağazayı kullanma iznin yok.");
            return true;
        }
        openShop(player);
        return true;
    }

    private void openShop(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, title);
        inv.setItem(11, rankItem(0));
        inv.setItem(13, rankItem(1));
        inv.setItem(15, rankItem(2));
        ItemStack info = new ItemStack(Material.BOOK);
        ItemMeta meta = info.getItemMeta();
        meta.setDisplayName(ChatColor.LIGHT_PURPLE + "MorCoin bakiyen");
        meta.setLore(List.of(ChatColor.GRAY + "Bakiye: " + ChatColor.GOLD
                + String.format(Locale.US, "%.0f", economy.getBalance(player))));
        info.setItemMeta(meta);
        inv.setItem(22, info);
        player.openInventory(inv);
    }

    private ItemStack rankItem(int index) {
        ItemStack item = new ItemStack(icons[index]);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + displayNames[index]);
        meta.setLore(List.of(
                ChatColor.GRAY + "Fiyat: " + ChatColor.GOLD + String.format(Locale.US, "%,d", prices[index]) + " MorCoin",
                ChatColor.YELLOW + "Satın almak için tıkla"
        ));
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!title.equals(event.getView().getTitle())) return;
        event.setCancelled(true);
        int slot = event.getRawSlot();
        int index = (slot == 11) ? 0 : (slot == 13) ? 1 : (slot == 15) ? 2 : -1;
        if (index < 0 || slot >= event.getView().getTopInventory().getSize()) return;
        purchase(player, index);
    }

    private void purchase(Player player, int index) {
        UUID uuid = player.getUniqueId();
        User user = luckPerms.getUserManager().getUser(uuid);
        if (user == null) {
            player.sendMessage(ChatColor.RED + "Rütbe bilgilerin yüklenemedi. Biraz sonra tekrar dene.");
            return;
        }

        String current = user.getPrimaryGroup().toLowerCase(Locale.ROOT);
        if (current.equals("admin") || current.equals("kurucu")) {
            player.sendMessage(ChatColor.RED + "Yetkili rütbeleri mağazadan satın alınamaz.");
            return;
        }

        int price = prices[index];
        if (economy.getBalance(player) < price) {
            player.sendMessage(ChatColor.RED + "Yeterli MorCoin'in yok. Gereken: "
                    + ChatColor.GOLD + price + " MorCoin");
            return;
        }

        String targetGroup = ranks[index];
        if (current.equals(targetGroup)) {
            player.sendMessage(ChatColor.YELLOW + "Bu rütbeye zaten sahipsin.");
            return;
        }

        var response = economy.withdrawPlayer(player, price);
        if (!response.transactionSuccess()) {
            player.sendMessage(ChatColor.RED + "Ödeme alınamadı: " + response.errorMessage);
            return;
        }

        try {
            // Yalnızca mağazadan alınan rütbe ebeveynlerini değiştir; diğer izin gruplarına dokunma.
            for (String rank : ranks) {
                user.data().clear(node -> node instanceof InheritanceNode
                        && ((InheritanceNode) node).getGroupName().equalsIgnoreCase(rank));
            }
            Node rankNode = InheritanceNode.builder(targetGroup).build();
            user.data().add(rankNode);
            luckPerms.getUserManager().saveUser(user);
            player.sendMessage(ChatColor.GREEN + displayNames[index]
                    + " rütbesini satın aldın! " + ChatColor.GOLD + price + " MorCoin"
                    + ChatColor.GREEN + " hesabından düşüldü.");
            player.closeInventory();
        } catch (Exception ex) {
            economy.depositPlayer(player, price);
            getLogger().severe("Rütbe verilemedi; ödeme iade edildi: " + ex.getMessage());
            player.sendMessage(ChatColor.RED + "Rütbe verilemedi. Ödemen iade edildi; yetkiliye haber ver.");
        }
    }
}
