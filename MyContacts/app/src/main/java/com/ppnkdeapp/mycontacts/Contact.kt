package com.ppnkdeapp.mycontacts

import kotlinx.serialization.Serializable

@Serializable
data class Contact(
    val Name: String,
    val email: String,
    val personal_id: String? = null,
    val group_id: Int? = null,
    val root_contact: Boolean? = null,
    val list_id: Int? = null
)


@Serializable
data class ContactsResponse(
    val contacts: List<Contact>
)