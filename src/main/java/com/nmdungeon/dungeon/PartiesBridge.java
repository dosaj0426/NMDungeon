package com.nmdungeon.dungeon;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Small reflection bridge so NMDungeon can soft-depend on Parties without shading its API. */
public final class PartiesBridge {
    public Optional<PartyContext> findParty(Player player) {
        if (Bukkit.getPluginManager().getPlugin("Parties") == null) {
            return Optional.empty();
        }
        try {
            Class<?> partiesClass = Class.forName("com.alessiodp.parties.api.Parties");
            Object api = partiesClass.getMethod("getApi").invoke(null);
            Object party = api.getClass().getMethod("getPartyOfPlayer", UUID.class).invoke(api, player.getUniqueId());
            if (party == null) {
                return Optional.empty();
            }
            UUID partyId = (UUID) party.getClass().getMethod("getId").invoke(party);
            UUID leaderId = (UUID) party.getClass().getMethod("getLeader").invoke(party);
            @SuppressWarnings("unchecked")
            Set<UUID> members = Set.copyOf((Set<UUID>) party.getClass().getMethod("getMembers").invoke(party));
            return Optional.of(new PartyContext(partyId, leaderId, members));
        } catch (ReflectiveOperationException | ClassCastException exception) {
            return Optional.empty();
        }
    }

    public boolean removePlayerFromParty(UUID playerId) {
        if (Bukkit.getPluginManager().getPlugin("Parties") == null) {
            return false;
        }
        try {
            Class<?> partiesClass = Class.forName("com.alessiodp.parties.api.Parties");
            Class<?> apiClass = Class.forName("com.alessiodp.parties.api.interfaces.PartiesAPI");
            Class<?> partyPlayerClass = Class.forName("com.alessiodp.parties.api.interfaces.PartyPlayer");
            Object api = partiesClass.getMethod("getApi").invoke(null);
            Object partyPlayer = apiClass.getMethod("getPartyPlayer", UUID.class).invoke(api, playerId);
            if (partyPlayer == null) {
                return false;
            }
            apiClass.getMethod("removePlayerFromParty", partyPlayerClass).invoke(api, partyPlayer);
            return true;
        } catch (ReflectiveOperationException exception) {
            return false;
        }
    }

    public record PartyContext(UUID partyId, UUID leaderId, Set<UUID> memberIds) {
    }
}
