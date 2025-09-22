package com.dddheroes.cinema.modules.seatsblocking.write.unblockseats

import com.dddheroes.cinema.shared.valueobjects.ScreeningId
import com.dddheroes.cinema.shared.valueobjects.SeatNumber
import java.time.Instant

data class UnblockSeats (
    val screeningId: ScreeningId,
    val seats: Set<SeatNumber>,
    val blockadeOwner: String,
    val issuedAt: Instant
)