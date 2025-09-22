package com.dddheroes.cinema.modules.seatsblocking.write.blockseat

import com.dddheroes.cinema.modules.seatsblocking.events.SeatBlocked
import com.dddheroes.cinema.modules.seatsblocking.events.SeatEvent
import com.dddheroes.cinema.modules.seatsblocking.events.SeatNotBlocked
import com.dddheroes.cinema.modules.seatsblocking.events.SeatNotUnblocked
import com.dddheroes.cinema.modules.seatsblocking.events.SeatPlaced
import com.dddheroes.cinema.modules.seatsblocking.events.SeatUnblocked
import com.dddheroes.cinema.modules.seatsblocking.write.placeseat.PlaceSeat
import com.dddheroes.cinema.shared.valueobjects.ScreeningId
import com.dddheroes.cinema.shared.valueobjects.SeatNumber
import com.dddheroes.sdk.application.CommandResult
import com.dddheroes.sdk.application.inSingleStreamTransaction
import com.dddheroes.sdk.application.resultOf
import com.dddheroes.sdk.application.toCommandResult
import com.dddheroes.sdk.domain.EventStreamId
import org.axonframework.commandhandling.CommandHandler
import org.axonframework.commandhandling.gateway.CommandGateway
import org.axonframework.eventsourcing.eventstore.EventStore
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.*
import java.time.Clock
import java.time.Instant

data class BlockSeat(
    val screeningId: ScreeningId,
    val seat: SeatNumber,
    val blockadeOwner: String,
    val issuedAt: Instant,
)

internal data class State(
    val placed: Boolean = false,
    val blockedBy: String? = null,
)

internal fun decide(
    command: BlockSeat,
    state: State,
): List<SeatEvent> {
    if (!state.placed) {
        return listOf(
            SeatNotBlocked(
                command.screeningId,
                command.seat,
                "Seat must be placed before it can be blocked",
                command.blockadeOwner,
                occurredAt = command.issuedAt,
            ),
        )
    }
    return when (state.blockedBy) {
        null -> listOf(
            SeatBlocked(
                command.screeningId,
                command.seat,
                command.blockadeOwner,
                occurredAt = command.issuedAt
            )
        )
        command.blockadeOwner -> emptyList()
        else if(command.blockadeOwner != state.blockedBy) -> {return listOf(SeatNotBlocked(
            command.screeningId,
            command.seat,
            "Seat is already blocked by ${state.blockedBy}",
            command.blockadeOwner,
            occurredAt = command.issuedAt,
        ))}
        else -> return listOf(SeatBlocked(command.screeningId,
            command.seat,
            "Seat is already blocked by ${state.blockedBy}",
            occurredAt = command.issuedAt,))
    }


}

internal fun evolve(
    state: State,
    event: SeatEvent,
): State =
    when (event) {
        is SeatUnblocked -> {
            state.copy(placed = false, null)
        }
        is SeatBlocked -> {
            state.copy(placed = true, event.blockadeOwner)
        }
        is SeatPlaced -> {
            state.copy(placed = true)
        }

        is SeatNotBlocked -> state.copy(placed = false)
        is SeatNotUnblocked -> state.copy(placed = false)
    }

@ConditionalOnProperty(name = ["slices.seatsblocking.write.blockseat.enabled"])
@Component
private class BlockSeatCommandHandler(
    val eventStore: EventStore,
) {
    @CommandHandler
    fun handle(command: BlockSeat): CommandResult =
        resultOf {
            val streamId = EventStreamId.of("Seat", command.screeningId, command.seat)

            val events = eventStore.inSingleStreamTransaction<SeatEvent>(streamId) { events ->
                val currentState = events
                    .fold(State()) { state, event -> evolve(state, event) }
                decide(command, currentState)
            }

            return events.toCommandResult()
        }
}

@ConditionalOnProperty(name = ["slices.seatsblocking.write.blockseat.enabled"])
@RestController
@RequestMapping("cinema/screenings/{screeningId}")
internal class BlockSeatRestApi(
    private val commandGateway: CommandGateway,
    private val clock: Clock,
) {
    data class Body(
        val blockadeOwner: String,
    )

    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PutMapping("/seats-blockades/{seat}")
    fun putSeatBlockade(
        @PathVariable screeningId: ScreeningId,
        @PathVariable seat: String,
        @RequestBody requestBody: Body,
    ): CommandResult =
        commandGateway.sendAndWait<CommandResult>(
            BlockSeat(
                screeningId = screeningId,
                seat = SeatNumber.from(seat),
                blockadeOwner = requestBody.blockadeOwner,
                issuedAt = clock.instant()
            )
        ).throwIfFailure()
}
