package com.dddheroes.cinema.modules.seatsblocking.read.getscreeningseats

import com.dddheroes.cinema.shared.valueobjects.ScreeningId
import com.dddheroes.cinema.modules.seatsblocking.events.SeatBlocked
import com.dddheroes.cinema.modules.seatsblocking.events.SeatEvent
import com.dddheroes.cinema.modules.seatsblocking.events.SeatPlaced
import com.dddheroes.cinema.modules.seatsblocking.events.SeatUnblocked
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.axonframework.config.ProcessingGroup
import org.axonframework.eventhandling.EventHandler
import org.axonframework.eventhandling.EventMessage
import org.axonframework.eventhandling.ResetHandler
import org.axonframework.eventhandling.async.SequencingPolicy
import org.axonframework.extensions.kotlin.query
import org.axonframework.queryhandling.QueryHandler
import org.axonframework.queryhandling.QueryGateway
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.CompletableFuture

data class GetScreeningSeats(
    val screeningId: ScreeningId
)

data class GetScreeningSeatsResult(val items: List<ScreeningSeatsReadModel.Seat>)

@Entity
@Table(name = "read_model_screening_seats")
data class ScreeningSeatsReadModel(
    @Id
    val screeningId: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    val seats: Map<String, Seat> = emptyMap()
) {
    data class Seat(val row: Int, val column: Int, val blockedBy: String?)
}

@ConditionalOnProperty(name = ["slices.seatsblocking.read.getscreeningseats.enabled"])
@Repository
private interface ScreeningSeatsReadModelRepository : JpaRepository<ScreeningSeatsReadModel, String>

@ConditionalOnProperty(name = ["slices.seatsblocking.read.getscreeningseats.enabled"])
@Component
@ProcessingGroup("Projection_SeatsReadModel")
private class ScreeningSeatsReadModelProjector(
    val repository: ScreeningSeatsReadModelRepository
) {

    @EventHandler
    fun handle(event: SeatPlaced) {
        val screeningId = event.screeningId.raw
        val state = repository.findById(screeningId).orElse(ScreeningSeatsReadModel(screeningId, emptyMap()))
        val updatedState = state.copy(
            seats = state.seats + (event.seat.toString() to ScreeningSeatsReadModel.Seat(
                event.seat.row,
                event.seat.column,
                null
            ))
        )
        repository.save(updatedState)
    }

    @EventHandler
    fun handle(event: SeatBlocked) {
        val screeningId = event.screeningId.raw
        val state = repository.findById(screeningId).orElse(ScreeningSeatsReadModel(screeningId, emptyMap()))
        val updatedState = state.copy(
            seats = state.seats + (event.seat.toString() to ScreeningSeatsReadModel.Seat(
                event.seat.row,
                event.seat.column,
                event.blockadeOwner

            ))
        )
        repository.save(updatedState)
    }

    @EventHandler
    fun handle(event: SeatUnblocked) {
        val screeningId = event.screeningId.raw
        val state = repository.findById(screeningId).orElse(ScreeningSeatsReadModel(screeningId, emptyMap()))
        val updatedState = state.copy(
            seats = state.seats + (event.seat.toString() to ScreeningSeatsReadModel.Seat(
                event.seat.row,
                event.seat.column,
                null
            ))
        )
        repository.save(updatedState)
    }

    @ResetHandler
    fun onReset() = repository.deleteAll()

    // Explanation: ordering of events - when it's necessary and when not
    @Bean
    fun screeningSeatsReadModelProjectorSequencingPolicy(): SequencingPolicy<EventMessage<SeatEvent>> {
        return SequencingPolicy { it.payload.screeningId }
    }
}

@ConditionalOnProperty(name = ["slices.seatsblocking.read.getscreeningseats.enabled"])
@Component
private class GetScreeningSeatsQueryHandler(
    private val repository: ScreeningSeatsReadModelRepository
) {

    @QueryHandler
    fun handle(query: GetScreeningSeats): GetScreeningSeatsResult {
        val readModel = repository.findById(query.screeningId.raw).orElse(null)
        return GetScreeningSeatsResult(readModel?.seats?.values?.toList() ?: emptyList())
    }
}

@ConditionalOnProperty(name = ["slices.seatsblocking.read.getscreeningseats.enabled"])
@RestController
@RequestMapping("/cinema")
internal class GetScreeningSeatsRestApi(private val queryGateway: QueryGateway) {

    @GetMapping("/screenings/{screeningId}/seats")
    fun getScreeningSeats(
        @PathVariable screeningId: String
    ): CompletableFuture<ResponseEntity<GetScreeningSeatsResult>> = queryGateway
        .query<GetScreeningSeatsResult, GetScreeningSeats>(GetScreeningSeats(ScreeningId.of(screeningId)))
        .thenApply { ResponseEntity.ok(it) }

}