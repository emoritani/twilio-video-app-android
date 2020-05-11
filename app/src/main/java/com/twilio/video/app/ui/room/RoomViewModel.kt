package com.twilio.video.app.ui.room

import androidx.lifecycle.LiveData
import androidx.lifecycle.Transformations
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.twilio.audioswitch.selection.AudioDeviceSelector
import com.twilio.video.Participant
import com.twilio.video.app.participant.ParticipantManager
import com.twilio.video.app.participant.ParticipantViewState
import com.twilio.video.app.participant.buildLocalParticipantViewState
import com.twilio.video.app.participant.buildParticipantViewState
import com.twilio.video.app.sdk.getFirstVideoTrack
import com.twilio.video.app.udf.BaseViewModel
import com.twilio.video.app.ui.room.RoomEvent.ConnectFailure
import com.twilio.video.app.ui.room.RoomEvent.Connected
import com.twilio.video.app.ui.room.RoomEvent.Connecting
import com.twilio.video.app.ui.room.RoomEvent.Disconnected
import com.twilio.video.app.ui.room.RoomEvent.DominantSpeakerChanged
import com.twilio.video.app.ui.room.RoomEvent.ParticipantEvent
import com.twilio.video.app.ui.room.RoomEvent.ParticipantEvent.MuteParticipant
import com.twilio.video.app.ui.room.RoomEvent.ParticipantEvent.NetworkQualityLevelChange
import com.twilio.video.app.ui.room.RoomEvent.ParticipantEvent.NewScreenTrack
import com.twilio.video.app.ui.room.RoomEvent.ParticipantEvent.ParticipantConnected
import com.twilio.video.app.ui.room.RoomEvent.ParticipantEvent.ParticipantDisconnected
import com.twilio.video.app.ui.room.RoomEvent.ParticipantEvent.ScreenTrackRemoved
import com.twilio.video.app.ui.room.RoomEvent.ParticipantEvent.VideoTrackUpdated
import com.twilio.video.app.ui.room.RoomEvent.TokenError
import com.twilio.video.app.ui.room.RoomViewEffect.ShowConnectFailureDialog
import com.twilio.video.app.ui.room.RoomViewEffect.ShowTokenErrorDialog
import com.twilio.video.app.ui.room.RoomViewEvent.ActivateAudioDevice
import com.twilio.video.app.ui.room.RoomViewEvent.Connect
import com.twilio.video.app.ui.room.RoomViewEvent.DeactivateAudioDevice
import com.twilio.video.app.ui.room.RoomViewEvent.Disconnect
import com.twilio.video.app.ui.room.RoomViewEvent.LocalVideoTrackPublished
import com.twilio.video.app.ui.room.RoomViewEvent.PinParticipant
import com.twilio.video.app.ui.room.RoomViewEvent.SelectAudioDevice
import kotlinx.coroutines.launch
import timber.log.Timber

class RoomViewModel(
    private val roomManager: RoomManager,
    private val audioDeviceSelector: AudioDeviceSelector,
    private val participantManager: ParticipantManager = ParticipantManager()
) : BaseViewModel<RoomViewEvent, RoomViewState, RoomViewEffect>(RoomViewState()) {

    // TODO Use another type of observable here like a Coroutine flow
    val roomEvents: LiveData<RoomEvent?> = Transformations.map(roomManager.viewEvents, ::observeRoomEvents)

    init {
        audioDeviceSelector.start { audioDevices, selectedDevice ->
            updateState { it.copy(
                selectedDevice = selectedDevice,
                availableAudioDevices = audioDevices)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioDeviceSelector.stop()
    }

    override fun processInput(viewEvent: RoomViewEvent) {
        Timber.d("View Event: $viewEvent")
        when (viewEvent) {
            is SelectAudioDevice -> {
                audioDeviceSelector.selectDevice(viewEvent.device)
            }
            ActivateAudioDevice -> { audioDeviceSelector.activate() }
            DeactivateAudioDevice -> { audioDeviceSelector.deactivate() }
            is Connect -> {
                connect(
                        viewEvent.identity,
                        viewEvent.roomName,
                        viewEvent.isNetworkQualityEnabled)
            }
            is LocalVideoTrackPublished -> updateLocalVideoTrack(viewEvent)
            is PinParticipant -> {
                participantManager.changePinnedParticipant(viewEvent.sid)
                updateParticipantViewState()
            }
            Disconnect -> roomManager.disconnect()
        }
    }

    private fun observeRoomEvents(roomEvent: RoomEvent?): RoomEvent? {
        Timber.d("observeRoomEvents: %s", roomEvent)
        when (roomEvent) {
            is Connecting -> {
                showConnectingViewState()
            }
            is Connected -> {
                showConnectedViewState(roomEvent.roomName)
                checkParticipants(roomEvent.participants)
                viewEffect { RoomViewEffect.Connected(roomEvent.room) }
            }
            is Disconnected -> {
                showLobbyViewState()
                updateState { it.copy(participantThumbnails = null, primaryParticipant = null) }
            }
            is DominantSpeakerChanged -> {
                participantManager.changeDominantSpeaker(roomEvent.newDominantSpeakerSid)
            }
            is ConnectFailure -> viewEffect {
                showLobbyViewState()
                ShowConnectFailureDialog
            }
            is TokenError -> viewEffect {
                showLobbyViewState()
                ShowTokenErrorDialog(roomEvent.serviceError)
            }
            is ParticipantEvent -> handleParticipantEvent(roomEvent)
        }
        return roomEvent
    }

    private fun handleParticipantEvent(participantEvent: ParticipantEvent) {
        when (participantEvent) {
            is ParticipantConnected -> addParticipant(participantEvent.participant)
            is VideoTrackUpdated -> updateVideoTrack(participantEvent.participant)
            is NewScreenTrack -> {
                participantManager.addParticipant(ParticipantViewState(
                        participantEvent.participant.sid,
                        participantEvent.participant.identity,
                        participantEvent.videoTrack,
                        isScreenSharing = true
                ))
            }
            is ScreenTrackRemoved -> {
                participantManager.removeScreenShareParticipant(participantEvent.sid)
            }
            is MuteParticipant -> {
                participantManager.muteParticipant(participantEvent.sid,
                        participantEvent.mute)
                updateParticipantViewState()
            }
            is NetworkQualityLevelChange -> {
                participantManager.updateNetworkQuality(participantEvent.sid,
                        participantEvent.networkQualityLevel)
                updateParticipantViewState()
            }
            is ParticipantDisconnected -> {
                participantManager.removeParticipant(participantEvent.sid)
                updateParticipantViewState()
            }
        }
    }

    private fun updateVideoTrack(participant: Participant) {
        participantManager.updateParticipantVideoTrack(participant.sid,
                participant.getFirstVideoTrack())
        updateParticipantViewState()
    }

    private fun addParticipant(participant: Participant) {
        val participantViewState = buildParticipantViewState(participant)
        participantManager.addParticipant(participantViewState)
        updateParticipantViewState()
    }

    private fun updateLocalVideoTrack(viewEvent: LocalVideoTrackPublished) {
        participantManager.getParticipant(viewEvent.sid)?.copy(
                videoTrack = viewEvent.localVideoTrack
        )?.let {
            participantManager.updateParticipant(it)
        } ?: Timber.d("Could not find a matching participant")
    }

    private fun showLobbyViewState() {
        viewEffect { RoomViewEffect.Disconnected }
        updateState { it.copy(
                isLobbyLayoutVisible = true,
                isConnectingLayoutVisible = false,
                isConnectedLayoutVisible = false
        ) }
        participantManager.clearParticipants()
    }

    private fun showConnectingViewState() {
        viewEffect { RoomViewEffect.Connecting }
        updateState { it.copy(
            isLobbyLayoutVisible = false,
            isConnectingLayoutVisible = true,
            isConnectedLayoutVisible = false
        ) }
    }

    private fun showConnectedViewState(roomName: String) {
        updateState { it.copy(
                title = roomName,
                isLobbyLayoutVisible = false,
                isConnectingLayoutVisible = false,
                isConnectedLayoutVisible = true
        ) }
    }

    private fun checkParticipants(participants: List<Participant>) {
        for ((index, participant) in participants.withIndex()) {
            val participantViewState = if (index == 0) {
                buildLocalParticipantViewState(participant, participant.identity)
            } else buildParticipantViewState(participant)
            participantManager.addParticipant(participantViewState)
        }
        updateParticipantViewState()
    }

    private fun updateParticipantViewState() {
        updateState { it.copy(participantThumbnails = participantManager.participantThumbnails) }
        updateState { it.copy(primaryParticipant = participantManager.primaryParticipant) }
    }

    private fun connect(
        identity: String,
        roomName: String,
        isNetworkQualityEnabled: Boolean
    ) =
        viewModelScope.launch {
            roomManager.connectToRoom(
                    identity,
                    roomName,
                    isNetworkQualityEnabled)
        }

    class RoomViewModelFactory(
        private val roomManager: RoomManager,
        private val audioDeviceSelector: AudioDeviceSelector
    ) : ViewModelProvider.Factory {

        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return RoomViewModel(roomManager, audioDeviceSelector) as T
        }
    }
}
