package com.dddheroes.cinema.modules.reservations.automation.blockingseats

import com.dddheroes.cinema.MessagingSpringBootTest
import com.dddheroes.cinema.modules.reservations.ReservationId
import com.dddheroes.cinema.modules.reservations.events.ReservationCancelled
import com.dddheroes.cinema.modules.reservations.events.ReservationStarted
import com.dddheroes.cinema.modules.reservations.write.cancelreservation.CancelReservation
import com.dddheroes.cinema.modules.reservations.write.confirmseats.ConfirmSeats
import com.dddheroes.cinema.modules.seatsblocking.events.SeatBlocked
import com.dddheroes.cinema.modules.seatsblocking.write.blockseats.BlockSeats
import com.dddheroes.cinema.modules.seatsblocking.write.unblockseats.UnblockSeats
import com.dddheroes.cinema.shared.valueobjects.ScreeningId
import com.dddheroes.cinema.shared.valueobjects.SeatNumber
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource

@TestPropertySource(properties = ["slices.reservations.automation.blockingseats.enabled=true"])
class BlockingReservationSeatsAutomationTest : MessagingSpringBootTest() {

    @Nested
    inner class HandleReservationStarted {

        @Test
        fun `when reservation cancelled then unblock seats for the reservation`() {
            // given
            val reservationId = ReservationId.random()
            val screeningId = ScreeningId.random()
            val seats = setOf(SeatNumber(0, 0), SeatNumber(0, 1))
            val now = currentTime()

            eventsOccurred(
                ReservationCancelled(
                    reservationId = reservationId,
                    screeningId = screeningId,
                    seats = seats,
                    reason = "Reason",
                    occurredAt = java.time.Instant.now()
                )
            )

            // when - event processed by the automation

            // then
            assertCommandExecuted(
                UnblockSeats(
                    screeningId = screeningId,
                    seats = seats,
                    blockadeOwner = "Reservation:${reservationId}",
                    issuedAt = now
                )
            )
        }

        @Test
        fun `when reservation started then block seats for the reservation`() {
            // given
            val reservationId = ReservationId.random()
            val screeningId = ScreeningId.random()
            val seats = setOf(SeatNumber(0, 0), SeatNumber(0, 1))
            val now = currentTime()

            eventsOccurred(
                ReservationStarted(
                    reservationId = reservationId,
                    screeningId = screeningId,
                    seats = seats,
                    occurredAt = now
                )
            )

            // when - event processed by the automation

            // then
            assertCommandExecuted(
                BlockSeats(
                    screeningId = screeningId,
                    seats = seats,
                    blockadeOwner = "Reservation:${reservationId}",
                    issuedAt = now
                )
            )
        }

        @Test
        fun `when reservation started but seats cannot be blocked then cancel reservation`() {
            // given
            val reservationId = ReservationId.random()
            val screeningId = ScreeningId.random()
            val seats = setOf(SeatNumber(0, 0), SeatNumber(0, 1))
            val now = currentTime()

            // Mock BlockSeats command to fail
            assumeCommandFailure(
                BlockSeats(
                    screeningId = screeningId,
                    seats = seats,
                    blockadeOwner = "Reservation:${reservationId}",
                    issuedAt = now
                )
            )

            eventsOccurred(
                ReservationStarted(
                    reservationId = reservationId,
                    screeningId = screeningId,
                    seats = seats,
                    occurredAt = now
                )
            )

            // when - event processed by the automation

            // then
            assertCommandExecuted(
                CancelReservation(
                    reservationId = reservationId,
                    reason = "Seats cannot be blocked.",
                    issuedAt = now
                )
            )
        }
    }

    @Nested
    inner class HandleSeatBlocked {

        @Test
        fun `when seat blocked for reservation then confirm seats`() {
            // given
            val reservationId = ReservationId.random()
            val screeningId = ScreeningId.random()
            val seat = SeatNumber(0, 0)
            val now = currentTime()

            eventsOccurred(
                SeatBlocked(
                    screeningId = screeningId,
                    seat = seat,
                    blockadeOwner = "Reservation:${reservationId}",
                    occurredAt = now
                )
            )

            // when - event processed by the automation

            // then
            assertCommandExecuted(
                ConfirmSeats(
                    reservationId = reservationId,
                    seats = setOf(seat),
                    issuedAt = now
                )
            )
        }

        @Test
        fun `when seat blocked for non-reservation then do not confirm seats`() {
            // given
            val screeningId = ScreeningId.random()
            val seat = SeatNumber(0, 0)
            val now = currentTime()

            eventsOccurred(
                SeatBlocked(
                    screeningId = screeningId,
                    seat = seat,
                    blockadeOwner = "Maintenance",
                    occurredAt = now
                )
            )

            // when - event processed by the automation

            // then
            assertCommandNotExecuted(
                ConfirmSeats(
                    reservationId = ReservationId("Maintenance"),
                    seats = setOf(seat),
                    issuedAt = now
                )
            )
        }
    }
}