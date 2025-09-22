package com.dddheroes.cinema.modules.reservations.automation.blockingseats

import com.dddheroes.cinema.modules.reservations.ReservationId
import com.dddheroes.cinema.modules.reservations.events.ReservationCancelled
import com.dddheroes.cinema.modules.reservations.events.ReservationStarted
import com.dddheroes.cinema.modules.reservations.write.cancelreservation.CancelReservation
import com.dddheroes.cinema.modules.reservations.write.confirmseats.ConfirmSeats
import com.dddheroes.cinema.modules.seatsblocking.events.SeatBlocked
import com.dddheroes.cinema.modules.seatsblocking.write.blockseats.BlockSeats
import com.dddheroes.cinema.modules.seatsblocking.write.unblockseats.UnblockSeats
import com.dddheroes.cinema.shared.valueobjects.ScreeningId
import com.dddheroes.sdk.application.CommandResult
import org.axonframework.commandhandling.gateway.CommandGateway
import org.axonframework.eventhandling.DisallowReplay
import org.axonframework.eventhandling.EventHandler
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@ConditionalOnProperty(name = ["slices.reservations.automation.blockingseats.enabled"])
@DisallowReplay
@Component
class BlockingReservationSeatsAutomation(
    private val commandGateway: CommandGateway,
) {
    @EventHandler
    fun handle(event: ReservationStarted) {
        val reservationId = event.reservationId
        try {
            val command =
                BlockSeats(
                    screeningId = ScreeningId.of(event.screeningId.raw),
                    seats = event.seats,
                    blockadeOwner = "Reservation:$reservationId",
                    issuedAt = event.occurredAt,
                )
            commandGateway.sendAndWait<CommandResult>(command).throwIfFailure()
        } catch (_: Exception) {
            val command =
                CancelReservation(
                    reservationId = reservationId,
                    reason = "Seats cannot be blocked.",
                    issuedAt = event.occurredAt,
                )
            commandGateway.sendAndWait<CommandResult>(command).throwIfFailure()
        }
    }

    @EventHandler
    fun handle(event: SeatBlocked) {
        val owner = event.blockadeOwner
        val reservationId = owner.takeIf { it.startsWith("Reservation:") }.let { it?.removePrefix("Reservation:") }
        if (reservationId != null) {
            val command =
                ConfirmSeats(
                    reservationId = ReservationId(reservationId),
                    seats = setOf(event.seat),
                    issuedAt = event.occurredAt,
                )
            commandGateway.sendAndWait<CommandResult>(command).throwIfFailure()
        }
    }

    @EventHandler
    fun handle(event: ReservationCancelled) {
        val reservationId = event.reservationId
        val command =
            UnblockSeats(
                screeningId = event.screeningId,
                seats = event.seats,
                blockadeOwner = "Reservation:$reservationId",
                issuedAt = event.occurredAt,
            )
        commandGateway.sendAndWait<CommandResult>(command).throwIfFailure()
    }
}
