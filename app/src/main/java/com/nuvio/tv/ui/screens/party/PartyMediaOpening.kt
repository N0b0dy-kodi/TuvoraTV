package com.nuvio.tv.ui.screens.party

import android.content.Context
import android.widget.Toast
import com.nuvio.tv.R
import com.nuvio.tv.core.party.PartyMedia
import com.nuvio.tv.core.party.PartyRepository
import com.nuvio.tv.core.party.PartyServerResolution

/**
 * "Watch with the party": opens the party's title as THIS device knows it. A media-server title is
 * mapped onto this device's own connection to the same server; without one the user is told why
 * instead of landing on a title that can never play. Returns whether anything was opened.
 */
internal fun openPartyMedia(
    context: Context,
    repository: PartyRepository,
    media: PartyMedia,
    open: (PartyMedia) -> Unit,
): Boolean = when (val resolution = repository.localize(media)) {
    PartyServerResolution.NotServer -> {
        open(media)
        true
    }
    is PartyServerResolution.Local -> {
        open(resolution.media)
        true
    }
    PartyServerResolution.NotConnected -> {
        Toast.makeText(context, context.getString(R.string.party_server_not_connected), Toast.LENGTH_LONG).show()
        false
    }
}
