package us.timinc.mc.cobblemon.spawnnotification

import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.api.events.entity.SpawnEvent
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.util.playSoundServer
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World
import us.timinc.mc.cobblemon.spawnnotification.config.ConfigBuilder
import us.timinc.mc.cobblemon.spawnnotification.config.SpawnNotificationConfig
import us.timinc.mc.cobblemon.spawnnotification.util.Broadcast
import us.timinc.mc.cobblemon.spawnnotification.util.DespawnReason
import us.timinc.mc.cobblemon.spawnnotification.util.PlayerUtil

object SpawnNotification : ModInitializer {
    const val MOD_ID = "spawn_notification"
    private var config: SpawnNotificationConfig = ConfigBuilder.load(SpawnNotificationConfig::class.java, MOD_ID)

    @JvmStatic
    var SHINY_SOUND_ID: Identifier = Identifier("$MOD_ID:pla_shiny")

    @JvmStatic
    var SHINY_SOUND_EVENT: SoundEvent = SoundEvent.of(SHINY_SOUND_ID)

    override fun onInitialize() {
        CobblemonEvents.POKEMON_ENTITY_SPAWN.subscribe { evt ->
            val pokemon = evt.entity.pokemon
            if (pokemon.isPlayerOwned()) return@subscribe

            val world = evt.ctx.world
            val pos = evt.ctx.position

            broadcastSpawn(evt)
            if (config.playShinySound && pokemon.shiny) {
                if (config.broadcastRangeEnabled) {
                    getValidPlayers(world, pos).forEach { playShinySoundClient(it) }
                } else {
                    playShinySound(world, pos)
                }
            }
        }
        CobblemonEvents.POKEMON_SENT_POST.subscribe { evt ->
            if (!config.playShinySoundPlayer) return@subscribe
            if (!evt.pokemon.shiny) return@subscribe

            playShinySound(evt.pokemonEntity.world, evt.pokemonEntity.blockPos)
        }
        CobblemonEvents.POKEMON_CAPTURED.subscribe { evt ->
            broadcastDespawn(evt.pokemon, DespawnReason.CAPTURED)
        }
        CobblemonEvents.POKEMON_FAINTED.subscribe { evt ->
            broadcastDespawn(evt.pokemon, DespawnReason.FAINTED)
        }

        ServerEntityEvents.ENTITY_UNLOAD.register { entity, _ ->
            if (entity !is PokemonEntity) return@register

            broadcastDespawn(entity.pokemon, DespawnReason.DESPAWNED)
        }
    }

    private fun broadcastDespawn(
        pokemon: Pokemon, reason: DespawnReason
    ) {
        if (!config.broadcastDespawns) return
        if (pokemon.isPlayerOwned()) return
        if (!(pokemon.shiny || pokemon.isLegendary())) return

        Broadcast.broadcastMessage(
            Text.translatable(
                "$MOD_ID.notification.${reason.translationKey}", pokemon.getDisplayName()
            )
        )
    }

    private fun broadcastSpawn(
        evt: SpawnEvent<PokemonEntity>
    ) {
        val pokemon = evt.entity.pokemon
        val pokemonName = pokemon.getDisplayName()

        val matchedLabel = pokemon.form.labels.firstOrNull { config.labelsForBroadcast.contains(it) }

        val message = when {
            matchedLabel != null && config.broadcastShiny && pokemon.shiny -> "$MOD_ID.notification.$matchedLabel.shiny"
            matchedLabel != null -> "$MOD_ID.notification.$matchedLabel"
            config.broadcastShiny && pokemon.shiny -> "$MOD_ID.notification.shiny"
            else -> return
        }

        var color = config.formatting["$matchedLabel${if (pokemon.shiny) ".shiny" else ""}"]
        if (color == null && pokemon.shiny) {
            color = config.formatting[matchedLabel]
        }
        if (color == null && pokemon.shiny) {
            color = config.formatting["shiny"]
        }
        val possibleFormatting = Formatting.byName(color)
        val formattedPokemonName = if (possibleFormatting == null) pokemonName else pokemonName.formatted(Formatting.byName(color))

        var messageComponent = Text.translatable(message, formattedPokemonName)
        val pos = evt.ctx.position
        if (config.broadcastCoords) {
            messageComponent = messageComponent.append(
                Text.translatable(
                    "$MOD_ID.notification.coords", pos.x, pos.y, pos.z
                )
            )
        }
        val level = evt.ctx.world
        if (config.broadcastBiome) {
            messageComponent = messageComponent.append(
                Text.translatable(
                    "$MOD_ID.notification.biome", Text.translatable("biome.${evt.ctx.biomeName.toTranslationKey()}")
                )
            )
        }

        if (config.announceCrossDimensions) {
            messageComponent = messageComponent.append(
                Text.translatable(
                    "$MOD_ID.notification.dimension",
                    Text.translatable("dimension.${level.dimensionKey.value.toTranslationKey()}")
                )
            )

            Broadcast.broadcastMessage(messageComponent)
        } else if (config.broadcastRangeEnabled) {
            Broadcast.broadcastMessage(getValidPlayers(level, pos), messageComponent)
        } else {
            Broadcast.broadcastMessage(level, messageComponent)
        }
    }

    private fun getValidPlayers(level: World, pos: BlockPos): List<ServerPlayerEntity> {
        return if (config.playerLimitEnabled) PlayerUtil.getValidPlayers(
            pos, config.broadcastRange, level.dimensionKey, config.playerLimit
        ) else PlayerUtil.getValidPlayers(pos, config.broadcastRange, level.dimensionKey)
    }

    private fun playShinySound(
        level: World, pos: BlockPos
    ) {
        level.playSoundServer(pos.toCenterPos(), SHINY_SOUND_EVENT, SoundCategory.NEUTRAL, 10f, 1f)
    }

    private fun playShinySoundClient(
        player: PlayerEntity
    ) {
        player.playSound(SHINY_SOUND_EVENT, SoundCategory.NEUTRAL, 10f, 1f)
    }
}