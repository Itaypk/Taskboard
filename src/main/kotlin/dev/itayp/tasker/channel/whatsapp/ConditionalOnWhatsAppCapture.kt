package dev.itayp.tasker.channel.whatsapp

import org.springframework.boot.autoconfigure.condition.ConditionOutcome
import org.springframework.boot.autoconfigure.condition.SpringBootCondition
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.core.type.AnnotatedTypeMetadata

/**
 * Registers the annotated bean only when WhatsApp quick-add is fully configured
 * ([WhatsAppProperties.captureActive]): the webhook's two secrets, the access token and the phone
 * number id. Binds the same properties class the webhook reads, so the two can't disagree.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Conditional(OnWhatsAppCaptureCondition::class)
annotation class ConditionalOnWhatsAppCapture

class OnWhatsAppCaptureCondition : SpringBootCondition() {
    override fun getMatchOutcome(context: ConditionContext, metadata: AnnotatedTypeMetadata): ConditionOutcome {
        val properties = Binder.get(context.environment)
            .bind("tasker.whatsapp", WhatsAppProperties::class.java)
            .orElseGet { WhatsAppProperties() }
        return if (properties.captureActive) {
            ConditionOutcome.match("WhatsApp is enabled with both secrets, an access token and a phone number id")
        } else {
            ConditionOutcome.noMatch("WhatsApp quick-add is not fully configured")
        }
    }
}
