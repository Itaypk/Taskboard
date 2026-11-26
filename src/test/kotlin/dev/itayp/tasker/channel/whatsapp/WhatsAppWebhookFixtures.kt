package dev.itayp.tasker.channel.whatsapp

/**
 * Deliveries in the shape the Meta dashboard's test sends produce (November 2026), with the
 * numbers and ids replaced by made-up ones.
 */
object WhatsAppWebhookFixtures {

    const val PHONE_NUMBER_ID = "106540352242922"
    const val MESSAGE_ID = "wamid.HBgLMTY1MDM4Nzk0MzkVAgASGBQzQTRBNjU5OUFFRTAzODEwMTQ0RgA="
    const val STATUS_ID = "wamid.HBgLMTY1MDM4Nzk0MzkVAgARGBI3MTE5MjU1NTI0RjE5RDBEQkYA"

    /** A user's text message. */
    val RECEIVED_TEXT = """
        {
          "object": "whatsapp_business_account",
          "entry": [{
            "id": "102290129340398",
            "changes": [{
              "value": {
                "messaging_product": "whatsapp",
                "metadata": { "display_phone_number": "15550783881", "phone_number_id": "$PHONE_NUMBER_ID" },
                "contacts": [{
                  "profile": { "name": "Sheena Nelson" },
                  "wa_id": "16505551234",
                  "user_id": "US.13491208655302741918",
                  "country_code": "US"
                }],
                "messages": [{
                  "from": "16505551234",
                  "from_user_id": "US.13491208655302741918",
                  "id": "$MESSAGE_ID",
                  "timestamp": "1795553890",
                  "text": { "body": "Buy milk tomorrow" },
                  "from_logical_id": "172129437925999",
                  "type": "text",
                  "internal_1p_only_data": {
                    "account_context": { "cs_id": "$PHONE_NUMBER_ID", "account_context_type": "non_paid_messaging" }
                  }
                }]
              },
              "field": "messages"
            }]
          }]
        }
    """.trimIndent()

    /** The status update for one of our own messages. */
    val READ_STATUS = """
        {
          "object": "whatsapp_business_account",
          "entry": [{
            "id": "102290129340398",
            "changes": [{
              "value": {
                "messaging_product": "whatsapp",
                "metadata": { "display_phone_number": "15550783881", "phone_number_id": "$PHONE_NUMBER_ID" },
                "contacts": [{ "wa_id": "16505551234", "user_id": "US.13491208655302741918" }],
                "statuses": [{
                  "id": "$STATUS_ID",
                  "status": "read",
                  "timestamp": "1795548404",
                  "recipient_id": "16505551234",
                  "recipient_user_id": "US.13491208655302741918",
                  "conversation": { "id": "e7fe40a48bb98289e2d5880e4a520000", "origin": { "type": "utility" } },
                  "pricing": { "billable": true, "pricing_model": "PMP", "category": "utility", "type": "regular" }
                }]
              },
              "field": "messages"
            }]
          }]
        }
    """.trimIndent()
}
