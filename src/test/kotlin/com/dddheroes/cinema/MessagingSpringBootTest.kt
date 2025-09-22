package com.dddheroes.cinema

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.dddheroes.sdk.application.CommandResult
import com.dddheroes.sdk.application.inMultiStreamTransaction
import com.dddheroes.sdk.application.sourceSingle
import com.dddheroes.sdk.domain.DomainEvent
import com.dddheroes.sdk.domain.EventStreamId
import org.awaitility.Awaitility
import org.axonframework.commandhandling.gateway.CommandGateway
import org.axonframework.deadline.DeadlineManager
import org.axonframework.eventhandling.GenericDomainEventMessage
import org.axonframework.eventsourcing.eventstore.EventStore
import org.axonframework.extensions.kotlin.query
import org.axonframework.messaging.responsetypes.ResponseTypes
import org.axonframework.queryhandling.QueryGateway
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.argThat
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture

@SpringBootTest
//@ActiveProfiles("test", "testcontainers")
 @ActiveProfiles("test")
abstract class MessagingSpringBootTest {
    @MockitoSpyBean
    @Autowired
    lateinit var commandGateway: CommandGateway

    @Autowired
    lateinit var eventStore: EventStore

    @MockitoSpyBean
    @Autowired
    lateinit var queryGateway: QueryGateway

    @MockitoSpyBean
    @Autowired
    lateinit var deadlineManager: DeadlineManager

    protected fun assertStreamEmpty(
        streamId: EventStreamId,
    ) {
        val sourcedEvents = streamEvents(streamId)
        assertThat(sourcedEvents).isEmpty()
    }

    protected fun assertStreamEvents(
        streamId: EventStreamId,
        expectedEvents: List<DomainEvent>
    ) {
        val sourcedEvents = streamEvents(streamId)
        assertThat(sourcedEvents).isEqualTo(expectedEvents)
    }

    protected fun assertLastStreamEvent(
        streamId: EventStreamId,
        expectedEvent: DomainEvent
    ) {
        val sourcedEvents = streamEvents(streamId)
        assertThat(sourcedEvents.last()).isEqualTo(expectedEvent)
    }

    protected fun assertLastStreamEvents(
        streamId: EventStreamId,
        vararg expectedEvents: DomainEvent
    ) {
        val sourcedEvents = streamEvents(streamId)
        val expectedEventsList = expectedEvents.toList()
        assertThat(sourcedEvents.takeLast(expectedEvents.size)).isEqualTo(expectedEventsList)
    }

    protected fun streamEvents(streamId: EventStreamId, startSequence: Int = 0) =
        eventStore.sourceSingle<DomainEvent>(streamId, startSequence)

    protected fun eventOccurred(eventsToStore: DomainEvent) =
        eventsOccurred(eventsToStore)

    protected fun eventsOccurred(vararg eventsToStore: DomainEvent) =
        eventsOccurred(eventsToStore.toList())

    protected fun eventsOccurred(eventsToStore: List<DomainEvent>) {
        val streamIds = eventsToStore.map { it.streamId }.distinct().toList()
        eventStore.inMultiStreamTransaction<DomainEvent>(streamIds) { existingEvents -> eventsToStore }
    }

    protected fun executeCommand(command: Any): CommandResult =
        commandGateway.sendAndWait(command)

    protected fun assumeCommandSuccess(command: Any) {
        Mockito.doReturn(CompletableFuture.completedFuture(CommandResult.Success))
            .`when`(commandGateway).send<CommandResult>(eq(command))
        Mockito.doReturn(CommandResult.Success)
            .`when`(commandGateway).sendAndWait<CommandResult>(eq(command))
        Mockito.doReturn(CommandResult.Success)
            .`when`(commandGateway).sendAndWait<CommandResult>(eq(command), any())
    }

    final inline fun <reified T : Any> assumeCommandSuccess() {
        Mockito.doReturn(CompletableFuture.completedFuture(CommandResult.Success))
            .`when`(commandGateway).send<CommandResult>(argThat { it is T })
        Mockito.doReturn(CommandResult.Success)
            .`when`(commandGateway).sendAndWait<CommandResult>(argThat { it is T })
        Mockito.doReturn(CommandResult.Success)
            .`when`(commandGateway).sendAndWait<CommandResult>(argThat { it is T }, any())
    }

    protected fun <T : Any> assumeCommandFailure(command: T) {
        val result = CommandResult.Failure("Simulated failure")
        Mockito.doReturn(CompletableFuture.completedFuture(result))
            .`when`(commandGateway).send<CommandResult>(eq(command))
        Mockito.doReturn(result)
            .`when`(commandGateway).sendAndWait<CommandResult>(eq(command))
        Mockito.doReturn(result)
            .`when`(commandGateway).sendAndWait<CommandResult>(eq(command), any())
    }

    final inline fun <reified T : Any> assumeCommandFailure() {
        Mockito.doReturn(CompletableFuture.completedFuture(CommandResult.Failure("Simulated failure")))
            .`when`(commandGateway).send<CommandResult>(argThat { it is T })
        Mockito.doReturn(CommandResult.Failure("Simulated failure"))
            .`when`(commandGateway).sendAndWait<CommandResult>(argThat { it is T })
    }

    protected fun assertCommandExecuted(command: Any) =
        awaitUntilAsserted { Mockito.verify(commandGateway).sendAndWait<CommandResult>(command) }

    protected fun assertCommandExecutedAsync(command: Any) =
        awaitUntilAsserted { Mockito.verify(commandGateway).send<CommandResult>(command) }

    protected fun assertCommandScheduled(command: Any, triggerAt: Instant) =
        awaitUntilAsserted {
            Mockito.verify(deadlineManager)
                .schedule(eq(triggerAt), any(), eq(command), any())
        }

    protected fun assertCommandNotExecuted(command: Any) = Awaitility.await()
        .during(Duration.ofSeconds(2))
        .atMost(Duration.ofSeconds(4))
        .untilAsserted { Mockito.verify(commandGateway, Mockito.never()).sendAndWait<CommandResult>(command) }

    final inline fun <reified R, reified Q : Any> executeQuery(query: Q): R {
        return queryGateway.query<R, Q>(query).join()
    }

    final inline fun <reified R, reified Q : Any> assumeQueryReturns(query: Q, result: R) {
        Mockito.doReturn(CompletableFuture.completedFuture<R>(result))
            .`when`(queryGateway).query(query, ResponseTypes.instanceOf(R::class.java))
    }

    protected fun awaitUntilAsserted(assertion: Runnable) {
        Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .untilAsserted { assertion.run() }
    }

    protected fun currentTimeIs(instant: Instant): Instant {
        GenericDomainEventMessage.clock = Clock.fixed(instant, ZoneOffset.UTC)
        return currentTime()
    }

    protected fun currentTime(): Instant = GenericDomainEventMessage.clock.instant()

    protected fun clock(): Clock = GenericDomainEventMessage.clock
}