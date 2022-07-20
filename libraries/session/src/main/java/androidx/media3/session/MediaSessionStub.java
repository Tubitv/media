/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.session;

import static androidx.media3.common.Player.COMMAND_ADJUST_DEVICE_VOLUME;
import static androidx.media3.common.Player.COMMAND_CHANGE_MEDIA_ITEMS;
import static androidx.media3.common.Player.COMMAND_PLAY_PAUSE;
import static androidx.media3.common.Player.COMMAND_PREPARE;
import static androidx.media3.common.Player.COMMAND_SEEK_BACK;
import static androidx.media3.common.Player.COMMAND_SEEK_FORWARD;
import static androidx.media3.common.Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM;
import static androidx.media3.common.Player.COMMAND_SEEK_TO_DEFAULT_POSITION;
import static androidx.media3.common.Player.COMMAND_SEEK_TO_MEDIA_ITEM;
import static androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT;
import static androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM;
import static androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS;
import static androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM;
import static androidx.media3.common.Player.COMMAND_SET_DEVICE_VOLUME;
import static androidx.media3.common.Player.COMMAND_SET_MEDIA_ITEM;
import static androidx.media3.common.Player.COMMAND_SET_MEDIA_ITEMS_METADATA;
import static androidx.media3.common.Player.COMMAND_SET_REPEAT_MODE;
import static androidx.media3.common.Player.COMMAND_SET_SHUFFLE_MODE;
import static androidx.media3.common.Player.COMMAND_SET_SPEED_AND_PITCH;
import static androidx.media3.common.Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS;
import static androidx.media3.common.Player.COMMAND_SET_VIDEO_SURFACE;
import static androidx.media3.common.Player.COMMAND_SET_VOLUME;
import static androidx.media3.common.Player.COMMAND_STOP;
import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkStateNotNull;
import static androidx.media3.common.util.Util.postOrRun;
import static androidx.media3.common.util.Util.postOrRunWithCompletion;
import static androidx.media3.common.util.Util.transformFutureAsync;
import static androidx.media3.session.SessionCommand.COMMAND_CODE_CUSTOM;
import static androidx.media3.session.SessionCommand.COMMAND_CODE_LIBRARY_GET_CHILDREN;
import static androidx.media3.session.SessionCommand.COMMAND_CODE_LIBRARY_GET_ITEM;
import static androidx.media3.session.SessionCommand.COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT;
import static androidx.media3.session.SessionCommand.COMMAND_CODE_LIBRARY_GET_SEARCH_RESULT;
import static androidx.media3.session.SessionCommand.COMMAND_CODE_LIBRARY_SEARCH;
import static androidx.media3.session.SessionCommand.COMMAND_CODE_LIBRARY_SUBSCRIBE;
import static androidx.media3.session.SessionCommand.COMMAND_CODE_LIBRARY_UNSUBSCRIBE;
import static androidx.media3.session.SessionCommand.COMMAND_CODE_SESSION_SET_RATING;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.TextUtils;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.core.util.ObjectsCompat;
import androidx.media.MediaSessionManager;
import androidx.media3.common.BundleListRetriever;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaLibraryInfo;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Rating;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.util.BundleableUtil;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.Util;
import androidx.media3.session.MediaLibraryService.LibraryParams;
import androidx.media3.session.MediaLibraryService.MediaLibrarySession;
import androidx.media3.session.MediaSession.ControllerCb;
import androidx.media3.session.MediaSession.ControllerInfo;
import androidx.media3.session.SessionCommand.CommandCode;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

/**
 * Class that handles incoming commands from {@link MediaController} and {@link MediaBrowser} to
 * both {@link MediaSession} and {@link MediaLibrarySession}.
 */
// We cannot create a subclass for library service specific function because AIDL doesn't support
// subclassing and it's generated stub class is an abstract class.
/* package */ final class MediaSessionStub extends IMediaSession.Stub {

  private static final String TAG = "MediaSessionStub";

  private final WeakReference<MediaSessionImpl> sessionImpl;
  private final MediaSessionManager sessionManager;
  private final ConnectedControllersManager<IBinder> connectedControllersManager;
  private final Set<ControllerInfo> pendingControllers;

  public MediaSessionStub(MediaSessionImpl sessionImpl) {
    // Initialize members with params.
    this.sessionImpl = new WeakReference<>(sessionImpl);
    sessionManager = MediaSessionManager.getSessionManager(sessionImpl.getContext());
    connectedControllersManager = new ConnectedControllersManager<>(sessionImpl);
    // ConcurrentHashMap has a bug in APIs 21-22 that can result in lost updates.
    pendingControllers = Collections.synchronizedSet(new HashSet<>());
  }

  public ConnectedControllersManager<IBinder> getConnectedControllersManager() {
    return connectedControllersManager;
  }

  private static void sendSessionResult(
      ControllerInfo controller, int sequenceNumber, SessionResult result) {
    try {
      checkStateNotNull(controller.getControllerCb()).onSessionResult(sequenceNumber, result);
    } catch (RemoteException e) {
      Log.w(TAG, "Failed to send result to controller " + controller, e);
    }
  }

  private static <K extends MediaSessionImpl>
      SessionTask<ListenableFuture<Void>, K> sendSessionResultSuccess(
          Consumer<PlayerWrapper> task) {
    return (sessionImpl, controller, sequenceNumber) -> {
      if (sessionImpl.isReleased()) {
        return Futures.immediateVoidFuture();
      }
      task.accept(sessionImpl.getPlayerWrapper());
      sendSessionResult(
          controller, sequenceNumber, new SessionResult(SessionResult.RESULT_SUCCESS));
      return Futures.immediateVoidFuture();
    };
  }

  private static <K extends MediaSessionImpl>
      SessionTask<ListenableFuture<Void>, K> sendSessionResultWhenReady(
          SessionTask<ListenableFuture<SessionResult>, K> task) {
    return (sessionImpl, controller, sequenceNumber) ->
        handleSessionTaskWhenReady(
            sessionImpl,
            controller,
            sequenceNumber,
            task,
            future -> {
              SessionResult result;
              try {
                result = checkNotNull(future.get(), "SessionResult must not be null");
              } catch (CancellationException unused) {
                result = new SessionResult(SessionResult.RESULT_INFO_SKIPPED);
              } catch (ExecutionException | InterruptedException exception) {
                result =
                    new SessionResult(
                        exception.getCause() instanceof UnsupportedOperationException
                            ? SessionResult.RESULT_ERROR_NOT_SUPPORTED
                            : SessionResult.RESULT_ERROR_UNKNOWN);
              }
              sendSessionResult(controller, sequenceNumber, result);
            });
  }

  private static <K extends MediaSessionImpl>
      SessionTask<ListenableFuture<SessionResult>, K> handleMediaItemsWhenReady(
          SessionTask<ListenableFuture<List<MediaItem>>, K> mediaItemsTask,
          MediaItemPlayerTask mediaItemPlayerTask) {
    return (sessionImpl, controller, sequenceNumber) -> {
      if (sessionImpl.isReleased()) {
        return Futures.immediateFuture(
            new SessionResult(SessionResult.RESULT_ERROR_SESSION_DISCONNECTED));
      }
      return transformFutureAsync(
          mediaItemsTask.run(sessionImpl, controller, sequenceNumber),
          mediaItems ->
              postOrRunWithCompletion(
                  sessionImpl.getApplicationHandler(),
                  () -> {
                    if (!sessionImpl.isReleased()) {
                      mediaItemPlayerTask.run(sessionImpl.getPlayerWrapper(), mediaItems);
                    }
                  },
                  new SessionResult(SessionResult.RESULT_SUCCESS)));
    };
  }

  private static void sendLibraryResult(
      ControllerInfo controller, int sequenceNumber, LibraryResult<?> result) {
    try {
      checkStateNotNull(controller.getControllerCb()).onLibraryResult(sequenceNumber, result);
    } catch (RemoteException e) {
      Log.w(TAG, "Failed to send result to browser " + controller, e);
    }
  }

  private static <V, K extends MediaLibrarySessionImpl>
      SessionTask<ListenableFuture<Void>, K> sendLibraryResultWhenReady(
          SessionTask<ListenableFuture<LibraryResult<V>>, K> task) {
    return (sessionImpl, controller, sequenceNumber) ->
        handleSessionTaskWhenReady(
            sessionImpl,
            controller,
            sequenceNumber,
            task,
            future -> {
              LibraryResult<V> result;
              try {
                result = checkNotNull(future.get(), "LibraryResult must not be null");
              } catch (CancellationException unused) {
                result = LibraryResult.ofError(LibraryResult.RESULT_INFO_SKIPPED);
              } catch (ExecutionException | InterruptedException unused) {
                result = LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN);
              }
              sendLibraryResult(controller, sequenceNumber, result);
            });
  }

  private <K extends MediaSessionImpl> void queueSessionTaskWithPlayerCommand(
      IMediaController caller,
      int sequenceNumber,
      @Player.Command int command,
      SessionTask<ListenableFuture<Void>, K> task) {
    long token = Binder.clearCallingIdentity();
    try {
      @SuppressWarnings({"unchecked", "cast.unsafe"})
      @Nullable
      K sessionImpl = (K) this.sessionImpl.get();
      if (sessionImpl == null || sessionImpl.isReleased()) {
        return;
      }
      @Nullable
      ControllerInfo controller = connectedControllersManager.getController(caller.asBinder());
      if (controller == null) {
        return;
      }
      postOrRun(
          sessionImpl.getApplicationHandler(),
          () -> {
            if (!connectedControllersManager.isPlayerCommandAvailable(controller, command)) {
              sendSessionResult(
                  controller,
                  sequenceNumber,
                  new SessionResult(SessionResult.RESULT_ERROR_PERMISSION_DENIED));
              return;
            }
            @SessionResult.Code
            int resultCode = sessionImpl.onPlayerCommandRequestOnHandler(controller, command);
            if (resultCode != SessionResult.RESULT_SUCCESS) {
              // Don't run rejected command.
              sendSessionResult(controller, sequenceNumber, new SessionResult(resultCode));
              return;
            }
            if (command == COMMAND_SET_VIDEO_SURFACE) {
              task.run(sessionImpl, controller, sequenceNumber);
            } else {
              connectedControllersManager.addToCommandQueue(
                  controller, () -> task.run(sessionImpl, controller, sequenceNumber));
            }
          });
    } finally {
      Binder.restoreCallingIdentity(token);
    }
  }

  private <K extends MediaSessionImpl> void dispatchSessionTaskWithSessionCommand(
      IMediaController caller,
      int sequenceNumber,
      @CommandCode int commandCode,
      SessionTask<ListenableFuture<Void>, K> task) {
    dispatchSessionTaskWithSessionCommand(
        caller, sequenceNumber, /* sessionCommand= */ null, commandCode, task);
  }

  private <K extends MediaSessionImpl> void dispatchSessionTaskWithSessionCommand(
      IMediaController caller,
      int sequenceNumber,
      SessionCommand sessionCommand,
      SessionTask<ListenableFuture<Void>, K> task) {
    dispatchSessionTaskWithSessionCommand(
        caller, sequenceNumber, sessionCommand, COMMAND_CODE_CUSTOM, task);
  }

  private <K extends MediaSessionImpl> void dispatchSessionTaskWithSessionCommand(
      IMediaController caller,
      int sequenceNumber,
      @Nullable SessionCommand sessionCommand,
      @CommandCode int commandCode,
      SessionTask<ListenableFuture<Void>, K> task) {
    long token = Binder.clearCallingIdentity();
    try {
      @SuppressWarnings({"unchecked", "cast.unsafe"})
      @Nullable
      K sessionImpl = (K) this.sessionImpl.get();
      if (sessionImpl == null || sessionImpl.isReleased()) {
        return;
      }
      @Nullable
      ControllerInfo controller = connectedControllersManager.getController(caller.asBinder());
      if (controller == null) {
        return;
      }
      postOrRun(
          sessionImpl.getApplicationHandler(),
          () -> {
            if (!connectedControllersManager.isConnected(controller)) {
              return;
            }
            if (sessionCommand != null) {
              if (!connectedControllersManager.isSessionCommandAvailable(
                  controller, sessionCommand)) {
                sendSessionResult(
                    controller,
                    sequenceNumber,
                    new SessionResult(SessionResult.RESULT_ERROR_PERMISSION_DENIED));
                return;
              }
            } else {
              if (!connectedControllersManager.isSessionCommandAvailable(controller, commandCode)) {
                sendSessionResult(
                    controller,
                    sequenceNumber,
                    new SessionResult(SessionResult.RESULT_ERROR_PERMISSION_DENIED));
                return;
              }
            }
            task.run(sessionImpl, controller, sequenceNumber);
          });
    } finally {
      Binder.restoreCallingIdentity(token);
    }
  }

  private static <T, K extends MediaSessionImpl> ListenableFuture<Void> handleSessionTaskWhenReady(
      K sessionImpl,
      ControllerInfo controller,
      int sequenceNumber,
      SessionTask<ListenableFuture<T>, K> task,
      Consumer<ListenableFuture<T>> futureResultHandler) {
    if (sessionImpl.isReleased()) {
      return Futures.immediateVoidFuture();
    }
    ListenableFuture<T> future = task.run(sessionImpl, controller, sequenceNumber);
    SettableFuture<Void> outputFuture = SettableFuture.create();
    future.addListener(
        () -> {
          if (sessionImpl.isReleased()) {
            outputFuture.set(null);
            return;
          }
          try {
            futureResultHandler.accept(future);
            outputFuture.set(null);
          } catch (Throwable error) {
            outputFuture.setException(error);
          }
        },
        MoreExecutors.directExecutor());
    return outputFuture;
  }

  public void connect(
      IMediaController caller,
      int controllerVersion,
      String callingPackage,
      int pid,
      int uid,
      Bundle connectionHints) {
    MediaSessionManager.RemoteUserInfo remoteUserInfo =
        new MediaSessionManager.RemoteUserInfo(callingPackage, pid, uid);
    ControllerInfo controllerInfo =
        new ControllerInfo(
            remoteUserInfo,
            controllerVersion,
            sessionManager.isTrustedForMediaControl(remoteUserInfo),
            new Controller2Cb(caller),
            connectionHints);
    @Nullable MediaSessionImpl sessionImpl = this.sessionImpl.get();
    if (sessionImpl == null || sessionImpl.isReleased()) {
      try {
        caller.onDisconnected(/* seq= */ 0);
      } catch (RemoteException e) {
        // Controller may be died prematurely.
        // Not an issue because we'll ignore it anyway.
      }
      return;
    }
    pendingControllers.add(controllerInfo);
    postOrRun(
        sessionImpl.getApplicationHandler(),
        () -> {
          boolean connected = false;
          try {
            pendingControllers.remove(controllerInfo);
            if (sessionImpl.isReleased()) {
              return;
            }
            IBinder callbackBinder =
                checkStateNotNull((Controller2Cb) controllerInfo.getControllerCb())
                    .getCallbackBinder();
            MediaSession.ConnectionResult connectionResult =
                sessionImpl.onConnectOnHandler(controllerInfo);
            // Don't reject connection for the request from trusted app.
            // Otherwise server will fail to retrieve session's information to dispatch
            // media keys to.
            if (!connectionResult.isAccepted && !controllerInfo.isTrusted()) {
              return;
            }
            if (!connectionResult.isAccepted) {
              // For the accepted controller, send non-null allowed commands to keep connection.
              connectionResult =
                  MediaSession.ConnectionResult.accept(
                      SessionCommands.EMPTY, Player.Commands.EMPTY);
            }
            SequencedFutureManager sequencedFutureManager;
            if (connectedControllersManager.isConnected(controllerInfo)) {
              Log.w(
                  TAG,
                  "Controller "
                      + controllerInfo
                      + " has sent connection"
                      + " request multiple times");
            }
            connectedControllersManager.addController(
                callbackBinder,
                controllerInfo,
                connectionResult.availableSessionCommands,
                connectionResult.availablePlayerCommands);
            sequencedFutureManager =
                checkStateNotNull(
                    connectedControllersManager.getSequencedFutureManager(controllerInfo));
            // If connection is accepted, notify the current state to the controller.
            // It's needed because we cannot call synchronous calls between
            // session/controller.
            PlayerWrapper playerWrapper = sessionImpl.getPlayerWrapper();
            PlayerInfo playerInfo = playerWrapper.createPlayerInfoForBundling();
            ConnectionState state =
                new ConnectionState(
                    MediaLibraryInfo.VERSION_INT,
                    MediaSessionStub.this,
                    sessionImpl.getSessionActivity(),
                    connectionResult.availableSessionCommands,
                    connectionResult.availablePlayerCommands,
                    playerWrapper.getAvailableCommands(),
                    sessionImpl.getToken().getExtras(),
                    playerInfo);

            // Double check if session is still there, because release() can be called in
            // another thread.
            if (sessionImpl.isReleased()) {
              return;
            }
            try {
              caller.onConnected(
                  sequencedFutureManager.obtainNextSequenceNumber(), state.toBundle());
              connected = true;
            } catch (RemoteException e) {
              // Controller may be died prematurely.
            }
            sessionImpl.onPostConnectOnHandler(controllerInfo);
          } finally {
            if (!connected) {
              try {
                caller.onDisconnected(/* seq= */ 0);
              } catch (RemoteException e) {
                // Controller may be died prematurely.
                // Not an issue because we'll ignore it anyway.
              }
            }
          }
        });
  }

  public void release() {
    List<ControllerInfo> controllers = connectedControllersManager.getConnectedControllers();
    for (ControllerInfo controller : controllers) {
      ControllerCb cb = controller.getControllerCb();
      if (cb != null) {
        try {
          cb.onDisconnected(/* seq= */ 0);
        } catch (RemoteException e) {
          // Ignore. We're releasing.
        }
      }
    }
    for (ControllerInfo controller : pendingControllers) {
      ControllerCb cb = controller.getControllerCb();
      if (cb != null) {
        try {
          cb.onDisconnected(/* seq= */ 0);
        } catch (RemoteException e) {
          // Ignore. We're releasing.
        }
      }
    }
  }

  //////////////////////////////////////////////////////////////////////////////////////////////
  // AIDL methods for session overrides
  //////////////////////////////////////////////////////////////////////////////////////////////

  @Override
  public void connect(
      @Nullable IMediaController caller,
      int sequenceNumber,
      @Nullable Bundle connectionRequestBundle)
      throws RuntimeException {
    if (caller == null || connectionRequestBundle == null) {
      return;
    }
    ConnectionRequest request;
    try {
      request = ConnectionRequest.CREATOR.fromBundle(connectionRequestBundle);
    } catch (RuntimeException e) {
      Log.w(TAG, "Ignoring malformed Bundle for ConnectionRequest", e);
      return;
    }
    int uid = Binder.getCallingUid();
    int callingPid = Binder.getCallingPid();
    long token = Binder.clearCallingIdentity();
    // Binder.getCallingPid() can be 0 for an oneway call from the remote process.
    // If it's the case, use PID from the ConnectionRequest.
    int pid = (callingPid != 0) ? callingPid : request.pid;
    try {
      connect(caller, request.version, request.packageName, pid, uid, request.connectionHints);
    } finally {
      Binder.restoreCallingIdentity(token);
    }
  }

  @Override
  public void stop(@Nullable IMediaController caller, int sequenceNumber) throws RemoteException {
    if (caller == null) {
      return;
    }
    queueSessionTaskWithPlayerCommand(
        caller, sequenceNumber, COMMAND_STOP, sendSessionResultSuccess(Player::stop));
  }

  @Override
  public void release(@Nullable IMediaController caller, int sequenceNumber)
      throws RemoteException {
    if (caller == null) {
      return;
    }
    long token = Binder.clearCallingIdentity();
    try {
      connectedControllersManager.removeController(caller.asBinder());
    } finally {
      Binder.restoreCallingIdentity(token);
    }
  }

  @Override
  public void onControllerResult(
      @Nullable IMediaController caller, int sequenceNumber, @Nullable Bundle sessionResultBundle) {
    if (caller == null || sessionResultBundle == null) {
      return;
    }
    SessionResult result;
    try {
      result = SessionResult.CREATOR.fromBundle(sessionResultBundle);
    } catch (RuntimeException e) {
      Log.w(TAG, "Ignoring malformed Bundle for SessionResult", e);
      return;
    }
    long token = Binder.clearCallingIdentity();
    try {
      @Nullable
      SequencedFutureManager manager =
          connectedControllersManager.getSequencedFutureManager(caller.asBinder());
      if (manager == null) {
        return;
      }
      manager.setFutureResult(sequenceNumber, result);
    } finally {
      Binder.restoreCallingIdentity(token);
    }
  }

  @Override
  public void play(@Nullable IMediaController caller, int sequenceNumber) throws RuntimeException {
    if (caller == null) {
      return;
    }
    queueSessionTaskWithPlayerCommand(
        caller, sequenceNumber, COMMAND_PLAY_PAUSE, sendSessionResultSuccess(Player::play));
  }

  @Override
  public void pause(@Nullable IMediaController caller, int sequenceNumber) throws RuntimeException {
    if (caller == null) {
      return;
    }
    queueSessionTaskWithPlayerCommand(
        caller, sequenceNumber, COMMAND_PLAY_PAUSE, sendSessionResultSuccess(Player::pause));
  }

  @Override
  public void prepare(@Nullable IMediaController caller, int sequenceNumber)
      throws RuntimeException {
    if (caller == null) {
      return;
    }
    queueSessionTaskWithPlayerCommand(
        caller, sequenceNumber, COMMAND_PREPARE, sendSessionResultSuccess(Player::prepare));
  }

  @Override
  public void seekToDefaultPosition(IMediaController caller, int sequenceNumber) {
    if (caller == null) {
      return;
    }
    queueSessionTaskWithPlayerCommand(
        caller,
        sequenceNumber,
        COMMAND_SEEK_TO_DEFAULT_POSITION,
        sendSessionResultSuccess(Player::seekToDefaultPosition));
  }

  @Override
  public void seekToDefaultPositionWithMediaItemIndex(
      IMediaController caller, int sequenceNumber, int mediaItemIndex) throws RemoteException {
    if (caller == null) {
      return;
    }
    queueSessionTaskWithPlayerCommand(
        caller,
        sequenceNumber,
        COMMAND_SEEK_TO_MEDIA_ITEM,
        sendSessionResultSuccess(player -> player.seekToDefaultPosition(mediaItemIndex)));
  }

  @Override
  public void seekTo(@Nullable IMediaController caller, int sequenceNumber, long positionMs)
      throws RuntimeException {
    if (caller == null) {
      return;
    }
    queueSessionTaskWithPlayerCommand(
        caller,
        sequenceNumber,
        COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        sendSessionResultSuccess(player -> player.seekTo(positionMs)));
  }

  @Override
  public void seekToWithMediaItemIndex(
      IMediaController caller, int sequenceNumber, int mediaItemIndex, long positionMs)
      throws RemoteException {
    if (caller == null) {
      return;
    }
    queueSessionTaskWithPlayerCommand(
        caller,
        sequenceNumber,
        COMMAND_SEEK_TO_MEDIA_ITEM,
        sendSessionResultSuccess(player -> player.seekTo(mediaItemIndex, positionMs)));
  }

  @Override
  public void seekBack(IMediaController caller, int sequenceNumber) {
    if (caller == null) {
      return;
    }
    queueSessionTaskWithPlayerCommand(
        caller, sequenceNumber, COMMAND_SEEK_BACK, sendSessionResultSuccess(Player::seekBack));
  }

  @Override
  public void seekForward(IMediaController caller, int sequenceNumber) {
    if (caller == null) {
      return;
    }
    queueSessionTaskWithPlayerCommand(
        caller,
        sequenceNumber,
        COMMAND_SEEK_FORWARD,
        sendSessionResultSuccess(Player::seekForward));
  }

  @Override
  public void onCustomCommand(
      @Nullable IMediaController caller,
      int sequenceNumber,
      @Nullable Bundle commandBundle,
      @Nullable Bundle args) {
    if (caller == null || commandBundle == null || args == null) {
      return;
    }
    SessionCommand command;
    try {
      command = SessionCommand.CREATOR.fromBundle(commandBundle);
    } catch (RuntimeException e) {
      Log.w(TAG, "Ignoring malformed Bundle for SessionCommand", e);
      return;
    }
    dispatchSessionTaskWithSessionCommand(
        caller,
        sequenceNumber,
        command,
        sendSessionResultWhenReady(
            (sessionImpl, controller, sequenceNum) ->
                sessionImpl.onCustomCommandOnHandler(controller, command, args)));
  }

  @Override
  public void setRatingWithMediaId(
      @Nullable IMediaController caller,
      int sequenceNumber,
      String mediaId,
      @Nullable Bundle ratingBundle) {
    if (caller == null || ratingBundle == null) {
      return;
    }
    if (TextUtils.isEmpty(mediaId)) {
      Log.w(TAG, "setRatingWithMediaId(): Ignoring empty mediaId");
      return;
    }
    Rating rating;
    try {
      rating = Rating.CREATOR.fromBundle(ratingBundle);
    } catch (RuntimeException e) {
      Log.w(TAG, "Ignoring malformed Bundle for Rating", e);
      return;
    }
    dispatchSessionTaskWithSessionCommand(
        caller,
        sequenceNumber,
        COMMAND_CODE_SESSION_SET_RATING,
        sendSessionResultWhenReady(
            (sessionImpl, controller, sequenceNum) ->
                sessionImpl.onSetRatingOnHandler(controller, mediaId, rating)));
  }

  @Override
  public void setRating(
      @Nullable IMediaController caller, int sequenceNumber, @Nullable Bundle ratingBundle) {
    if (caller == null || ratingBundle == null) {
      return;
    }
    Rating rating;
    try {
      rating = Rating.CREATOR.fromBundle(ratingBundle);
    } catch (RuntimeException e) {
      Log.w(TAG, "Ignoring malformed Bundle for Rating", e);
      return;
    }
    dispatchSessionTaskWithSessionCommand(
        caller,
        sequenceNumber,
        COMMAND_CODE_SESSION_SET_RATING,
        sendSessionResultWhenReady(
            (sessionImpl, controller, sequenceNum) ->
                sessionImpl.onSetRatingOnHandler(controller, rating)));
  }

  @Override
  public void setPlaybackSpeed(@Nullable IMediaController caller, int sequenceNumber, float speed) {
    if (caller == null) {
      return;
    }
    queueSessionTaskWithPlayerCommand(
        caller,
        sequenceNumber,
        COMMAND_SET_SPEED_AND_PITCH,
        sendSessionResultSuccess(player -> player.setPlaybackSpeed(speed)));
  }

  @Override
  public void setPlaybackParameters(
      @Nullable IMediaController caller, int sequenceNumber, Bundle playbackParametersBundle) {
    if (caller == null || playbackParametersBundle == null) {
      return;
    }
    PlaybackParameters playbackParameters =
        PlaybackParameters.CREATOR.fromBundle(playbackParametersBundle);
    queueSessionTaskWithPlayerCommand(
        caller,
        sequenceNumber,
        COMMAND_SET_SPEED_AND_PITCH,
        sendSessionResultSuccess(player -> player.setPlaybackParameters(playbackParameters)));
  }

  @Override
  public void setMediaItem(
      @Nullable IMediaController caller, int sequenceNumber, @Nullable Bundle mediaItemBundle) {
    if (caller == null || mediaItemBundle == null) {
      return;
    }
    MediaItem mediaItem;
    try {
      mediaItem = MediaItem.CREATOR.fromBundle(mediaItemBundle);
    } catch (RuntimeException e) {
      Log.w(TAG, "Ignoring malformed Bundle for MediaItem", e);
      return;
    }
    queueSessionTaskWithPlayerCommand(
        caller,
        sequenceNumber,
        COMMAND_SET_MEDIA_ITEM,
        sendSessionResultWhenReady(
            handleMediaItemsWhenReady(
                (sessionImpl, controller, sequenceNum) ->
                    sessionImpl.onAddMediaItemsOnHandler(controller, ImmutableList.of(mediaItem)),
                Player::setMediaItems)));
  }

  @Override
  public void setMediaItemWithStartPosition(
      @Nullable IMediaController caller,
      int sequenceNumber,
      @Nullable Bundle mediaItemBundle,
      long startPositionMs) {
    if (caller == null || mediaItemBundle == null) {
      return;
    }
    MediaItem mediaItem;
    try {
      mediaItem = MediaItem.CREATOR.fromBundle(mediaItemBundle);
    } catch (RuntimeException e) {
      Log.w(TAG, "Ignoring malformed Bundle for MediaItem", e);
      return;
    }
    queueSessionTaskWithPlayerCommand(
        caller,
        sequenceNumber,
        COMMAND_SET_MEDIA_ITEM,
        sendSessionResultWhenReady(
            handleMediaItemsWhenReady(
                (sessionImpl, controller, sequenceNum) ->
                    sessionImpl.onAddMediaItemsOnHandler(controller, ImmutableList.of(mediaItem)),
                (player, mediaItems) ->
                    player.setMediaItems(mediaItems, /* startIndex= */ 0, startPositionMs))));
  }

  @Override
  public void setMediaItemWithResetPosition(
      @Nullable IMediaController caller,
      int sequenceNumber,
      @Nullable Bundle mediaItemBundle,
      boolean resetPosition) {
    if (caller == null || mediaItemBundle == null) {
      return;
    }
    MediaItem mediaItem;
    try {
      mediaItem = MediaItem.CREATOR.fromBundle(mediaItemBundle);
    } catch (RuntimeException e) {
      Log.w(TAG, "Ignoring malformed Bundle for MediaItem", e);
      return;
    }
    queueSessionTaskWithPlayerCommand(
        caller,
        sequenceNumber,
        COMMAND_SET_MEDIA_ITEM,
        sendSessionResultWhenReady(
            handleMediaItemsWhenReady(
                (sessionImpl, controller, sequenceNum) ->
                    sessionImpl.onAddMediaItemsOnHandler(controller, ImmutableList.of(mediaItem)),
                (player, mediaItems) -> player.setMediaItems(mediaItems, resetPosition))));
  }

  @Override
  public void setMediaItems(
      @Nullable IMediaController caller,
      int sequenceNumber,
      @Nullable IBinder mediaItemsRetriever) {
    if (caller == null || mediaItemsRetriever == null) {
      return;
    }
    List<MediaItem> mediaItemList;
    try {
      mediaItemList =
          BundleableUtil.fromBundleList(
              MediaItem.CREATOR, BundleListRetriever.getList(mediaItemsRetriever));
    } catch (RuntimeException e) {
      Log.w(TAG, "Ignoring malformed Bundle for MediaItem", e);
      return;
    }

    queueSessionTaskWithPlayerCommand(
        caller,
        sequenceNumber,
        COMMAND_CHANGE_MEDIA_ITEMS,
        sendSessionResultWhenReady(
            handleMediaItemsWhenReady(
                (sessionImpl, controller, sequenceNum) ->
                    sessionImpl.onAddMediaItemsOnHandler(controller, mediaItemList),
                Player::setMediaItems)));
  }

  @Override
  public void setMediaItemsWithResetPosition(
      @Nullable IMediaController caller,
      int sequenceNumber,
      @Nullable IBinder mediaItemsRetriever,
      boolean resetPosition) {
    if (caller == null || mediaItemsRetriever == null) {
      return;
    }
    List<MediaItem> mediaItemList;
    try {
      mediaItemList =
          BundleableUtil.fromBundleList(
              MediaItem.CREATOR, BundleListRetriever.getList(mediaItemsRetriever));
    } catch (RuntimeException e) {
      Log.w(TAG, "Ignoring malformed Bundle for MediaItem", e);
      return;
    }
    queueSessionTaskWithPlayerCommand(
        caller,
        sequenceNumber,
        COMMAND_CHANGE_MEDIA_ITEMS,
        sendSessionResultWhenReady(
            handleMediaItemsWhenReady(
                (sessionImpl, controller, sequenceNum) ->
                    sessionImpl.onAddMediaItemsOnHandler(controller, mediaItemList),
                (player, mediaItems) -> player.setMediaItems(mediaItems, resetPosition))));
  }

  @Override
  public void setMediaItemsWithStartIndex(
      @Nullable IMediaController caller,
      int sequenceNumber,
      @Nullable IBinder mediaItemsRetriever,
      int startIndex,
      long startPositionMs) {
    if (caller == null || mediaItemsRetriever == null) {
      return;
    }
    List<MediaItem> mediaItemList;
    try {
      mediaItemList =
          BundleableUtil.fromBundleList(
              MediaItem.CREATOR, BundleListRetriever.getList(mediaItemsRetriever));
    } catch (RuntimeException e) {
      Log.w(TAG, "Ignoring malformed Bundle for MediaItem", e);
      return;
    }
    queueSessionTaskWithPlayerCommand(
        caller,
        sequenceNumber,
        COMMAND_CHANGE_MEDIA_ITEMS,
        sendSessionResultWhenReady(
            handleMediaItemsWhenReady(
                (sessionImpl, controller, sequenceNum) ->
                    sessionImpl.onAddMediaItemsOnHandler(controller, mediaItemList),
                (player, mediaItems) ->
                    player.setMediaItems(mediaItems, startIndex, startPositionMs))));
  }

  @Override
  public void setPlaylistMetadata(
      @Nullable IMediaController caller,
      int sequenceNumber,
      @Nullable Bundle playlistMetadataBundle) {
    if (caller == null || playlistMetadataBundle == null) {
      return;
    }
    MediaMetadata playlistMetadata;
    try {
      playlistMetadata = MediaMetadata.CREATOR.fromBundle(playlistMetadataBundle);
    } catch (RuntimeException e) {
      Log.w(TAG, "Ignoring malformed Bundle for MediaMetadata", e);
      return;
    }
    queueSessionTaskWithPlayerCommand(
        caller,
        sequenceNumber,
        COMMAND_SET_MEDIA_ITEMS_METADATA,
        sendSessionResultSuccess(player -> player.setPlaylistMetadata(playlistMetadata)));
  }

  @Override
  public void addMediaItem(
      @Nullable IMediaController caller, int sequenceNumber, Bundle mediaItemBundle) {
    if (caller == null || mediaItemBundle == null) {
      return;
    }
    MediaItem mediaItem;
    try {
      mediaItem = MediaItem.CREATOR.fromBundle(mediaItemBundle);
    } catch (RuntimeException e) {
      Log.w(TAG, "Ignoring malformed Bundle for MediaItem", e);
      return;
    }
    queueSessionTaskWithPlayerCommand(
        caller,
        sequenceNumber,
        COMMAND_CHANGE_MEDIA_ITEMS,
        sendSessionResultWhenReady(
            handleMediaItemsWhenReady(
                (sessionImpl, controller, sequenceNum) ->
                    sessionImpl.onAddMediaItemsOnHandler(controller, ImmutableList.of(mediaItem)),
                Player::addMediaItems)));
  }

  @Override
  public void addMediaItemWithIndex(
      @Nullable IMediaController caller, int sequenceNumber, int index, Bundle mediaItemBundle) {
    if (caller == null || mediaItemBundle == null) {
      return;
    }
    MediaItem mediaItem;
    try {
      mediaItem = MediaItem.CREATOR.fromBundle(mediaItemBundle);
    } catch (RuntimeException e) {
      Log.w(TAG, "Ignoring malformed Bundle for MediaItem", e);
      return;
    }
    queueSessionTaskWithPlayerCommand(
        caller,
        sequenceNumber,
        COMMAND_CHANGE_MEDIA_ITEMS,
        sendSessionResultWhenReady(
            handleMediaItemsWhenReady(
                (sessionImpl, controller, sequenceNum) ->
                    sessionImpl.onAddMediaItemsOnHandler(controller, ImmutableList.of(mediaItem)),
                (player, mediaItems) -> player.addMediaItems(index, mediaItems))));
  }

  @Override
  public void addMediaItems(
      @Nullable IMediaController caller,
      int sequenceNumber,
      @Nullable IBinder mediaItemsRetriever) {
    if (caller == null || mediaItemsRetriever == null) {
      return;
    }
    List<MediaItem> mediaItems;
    try {
      mediaItems =
          BundleableUtil.fromBundleList(
              MediaItem.CREATOR, BundleListRetriever.getList(mediaItemsRetriever));
    } catch (RuntimeException e) {
      Log.w(TAG, "Ignoring malformed Bundle for MediaItem", e);
      return;
    }
    queueSessionTaskWithPlayerCommand(
        caller,
        sequenceNumber,
        COMMAND_CHANGE_MEDIA_ITEMS,
        sendSessionResultWhenReady(
            handleMediaItemsWhenReady(
                (sessionImpl, controller, sequenceNum) ->
                    sessionImpl.onAddMediaItemsOnHandler(controller, mediaItems),
                Player::addMediaItems)));
  }

  @Override
  public void addMediaItemsWithIndex(
      @Nullable IMediaController caller,
      int sequenceNumber,
      int index,
      @Nullable IBinder mediaItemsRetriever) {
    if (caller == null || mediaItemsRetriever == null) {
      return;
    }
    List<MediaItem> mediaItems;
    try {
      mediaItems =
          BundleableUtil.fromBundleList(
              MediaItem.CREATOR, BundleListRetriever.getList(mediaItemsRetriever));
    } catch (RuntimeException e) {
      Log.w(TAG, "Ignoring malformed Bundle for MediaItem", e);
      return;
    }
    queueSessionTaskWithPlayerCommand(
        caller,
        sequenceNumber,
        COMMAND_CHANGE_MEDIA_ITEMS,
        sendSessionResultWhenReady(
            handleMediaItemsWhenReady(
                (sessionImpl, controller, sequenceNum) ->
                    sessionImpl.onAddMediaItemsOnHandler(controller, mediaItems),
                (player, items) -> player.addMediaItems(index, items))));
  }

  @Override
  public void removeMediaItem(@Nullable IMediaController caller, int sequenceNumber, int index) {
    if (caller == null) {
      return;
    }
    queueSessionTaskWithPlayerCommand(
        caller,
        sequenceNumber,
        COMMAND_CHANGE_MEDIA_ITEMS,
        sendSessionResultSuccess(player -> player.removeMediaItem(index)));
  }

  @Override
  public void removeMediaItems(
      @Nullable IMediaController caller, int sequenceNumber, int fromIndex, int toIndex) {
    if (caller == null) {
      return;
    }
    queueSessionTaskWithPlayerCommand(
        caller,
        sequenceNumber,
        COMMAND_CHANGE_MEDIA_ITEMS,
        sendSessionResultSuccess(player -> player.removeMediaItems(fromIndex, toIndex)));
  }

  @Override
  public void clearMediaItems(@Nullable IMediaController caller, int sequenceNumber) {
    if (caller == null) {
      return;
    }
    queueSessionTaskWithPlayerCommand(
        caller,
        sequenceNumber,
        COMMAND_CHANGE_MEDIA_ITEMS,
        sendSessionResultSuccess(Player::clearMediaItems));
  }

  @Override
  public void moveMediaItem(
      @Nullable IMediaController caller, int sequenceNumber, int currentIndex, int newIndex) {
    if (caller == null) {
      return;
    }
    queueSessionTaskWithPlayerCommand(
        caller,
        sequenceNumber,
        COMMAND_CHANGE_MEDIA_ITEMS,
        sendSessionResultSuccess(player -> player.moveMediaItem(currentIndex, newIndex)));
  }

  @Override
  public void moveMediaItems(
      @Nullable IMediaController caller,
      int sequenceNumber,
      int fromIndex,
      int toIndex,
      int newIndex) {
    if (caller == null) {
      return;
    }
    queueSessionTaskWithPlayerCommand(
        caller,
        sequenceNumber,
        COMMAND_CHANGE_MEDIA_ITEMS,
        sendSessionResultSuccess(player -> player.moveMediaItems(fromIndex, toIndex, newIndex)));
  }

  @Override
  public void seekToPreviousMediaItem(@Nullable IMediaController caller, int sequenceNumber) {
    if (caller == null) {
      return;
    }
    queueSessionTaskWithPlayerCommand(
        caller,
        sequenceNumber,
        COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        sendSessionResultSuccess(Player::seekToPreviousMediaItem));
  }

  @Override
  public void seekToNextMediaItem(@Nullable IMediaController caller, int sequenceNumber) {
    if (caller == null) {
      return;
    }
    queueSessionTaskWithPlayerCommand(
        caller,
        sequenceNumber,
        COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        sendSessionResultSuccess(Player::seekToNextMediaItem));
  }

  @Override
  public void seekToPrevious(@Nullable IMediaController caller, int sequenceNumber) {
    if (caller == null) {
      return;
    }
    queueSessionTaskWithPlayerCommand(
        caller,
        sequenceNumber,
        COMMAND_SEEK_TO_PREVIOUS,
        sendSessionResultSuccess(Player::seekToPrevious));
  }

  @Override
  public void seekToNext(@Nullable IMediaController caller, int sequenceNumber) {
    if (caller == null) {
      return;
    }
    queueSessionTaskWithPlayerCommand(
        caller, sequenceNumber, COMMAND_SEEK_TO_NEXT, sendSessionResultSuccess(Player::seekToNext));
  }

  @Override
  public void setRepeatMode(
      @Nullable IMediaController caller, int sequenceNumber, @Player.RepeatMode int repeatMode) {
    if (caller == null) {
      return;
    }
    queueSessionTaskWithPlayerCommand(
        caller,
        sequenceNumber,
        COMMAND_SET_REPEAT_MODE,
        sendSessionResultSuccess(player -> player.setRepeatMode(repeatMode)));
  }

  @Override
  public void setShuffleModeEnabled(
      @Nullable IMediaController caller, int sequenceNumber, boolean shuffleModeEnabled) {
    if (caller == null) {
      return;
    }
    queueSessionTaskWithPlayerCommand(
        caller,
        sequenceNumber,
        COMMAND_SET_SHUFFLE_MODE,
        sendSessionResultSuccess(player -> player.setShuffleModeEnabled(shuffleModeEnabled)));
  }

  @Override
  public void setVideoSurface(
      @Nullable IMediaController caller, int sequenceNumber, @Nullable Surface surface) {
    if (caller == null) {
      return;
    }
    queueSessionTaskWithPlayerCommand(
        caller,
        sequenceNumber,
        COMMAND_SET_VIDEO_SURFACE,
        sendSessionResultSuccess(player -> player.setVideoSurface(surface)));
  }

  @Override
  public void setVolume(@Nullable IMediaController caller, int sequenceNumber, float volume) {
    if (caller == null) {
      return;
    }
    queueSessionTaskWithPlayerCommand(
        caller,
        sequenceNumber,
        COMMAND_SET_VOLUME,
        sendSessionResultSuccess(player -> player.setVolume(volume)));
  }

  @Override
  public void setDeviceVolume(@Nullable IMediaController caller, int sequenceNumber, int volume) {
    if (caller == null) {
      return;
    }
    queueSessionTaskWithPlayerCommand(
        caller,
        sequenceNumber,
        COMMAND_SET_DEVICE_VOLUME,
        sendSessionResultSuccess(player -> player.setDeviceVolume(volume)));
  }

  @Override
  public void increaseDeviceVolume(@Nullable IMediaController caller, int sequenceNumber) {
    if (caller == null) {
      return;
    }
    queueSessionTaskWithPlayerCommand(
        caller,
        sequenceNumber,
        COMMAND_ADJUST_DEVICE_VOLUME,
        sendSessionResultSuccess(Player::increaseDeviceVolume));
  }

  @Override
  public void decreaseDeviceVolume(@Nullable IMediaController caller, int sequenceNumber) {
    if (caller == null) {
      return;
    }
    queueSessionTaskWithPlayerCommand(
        caller,
        sequenceNumber,
        COMMAND_ADJUST_DEVICE_VOLUME,
        sendSessionResultSuccess(Player::decreaseDeviceVolume));
  }

  @Override
  public void setDeviceMuted(@Nullable IMediaController caller, int sequenceNumber, boolean muted) {
    if (caller == null) {
      return;
    }
    queueSessionTaskWithPlayerCommand(
        caller,
        sequenceNumber,
        COMMAND_SET_DEVICE_VOLUME,
        sendSessionResultSuccess(player -> player.setDeviceMuted(muted)));
  }

  @Override
  public void setPlayWhenReady(
      @Nullable IMediaController caller, int sequenceNumber, boolean playWhenReady) {
    if (caller == null) {
      return;
    }
    queueSessionTaskWithPlayerCommand(
        caller,
        sequenceNumber,
        COMMAND_PLAY_PAUSE,
        sendSessionResultSuccess(player -> player.setPlayWhenReady(playWhenReady)));
  }

  @Override
  public void flushCommandQueue(@Nullable IMediaController caller) {
    if (caller == null) {
      return;
    }
    long token = Binder.clearCallingIdentity();
    try {
      @Nullable MediaSessionImpl sessionImpl = this.sessionImpl.get();
      if (sessionImpl == null || sessionImpl.isReleased()) {
        return;
      }
      ControllerInfo controllerInfo = connectedControllersManager.getController(caller.asBinder());
      if (controllerInfo != null) {
        postOrRun(
            sessionImpl.getApplicationHandler(),
            () -> connectedControllersManager.flushCommandQueue(controllerInfo));
      }
    } finally {
      Binder.restoreCallingIdentity(token);
    }
  }

  @Override
  public void setTrackSelectionParameters(
      @Nullable IMediaController caller, int sequenceNumber, Bundle trackSelectionParametersBundle)
      throws RemoteException {
    if (caller == null) {
      return;
    }
    TrackSelectionParameters trackSelectionParameters;
    try {
      trackSelectionParameters =
          TrackSelectionParameters.fromBundle(trackSelectionParametersBundle);
    } catch (RuntimeException e) {
      Log.w(TAG, "Ignoring malformed Bundle for TrackSelectionParameters", e);
      return;
    }
    queueSessionTaskWithPlayerCommand(
        caller,
        sequenceNumber,
        COMMAND_SET_TRACK_SELECTION_PARAMETERS,
        sendSessionResultSuccess(
            player -> player.setTrackSelectionParameters(trackSelectionParameters)));
  }

  //////////////////////////////////////////////////////////////////////////////////////////////
  // AIDL methods for LibrarySession overrides
  //////////////////////////////////////////////////////////////////////////////////////////////

  @Override
  public void getLibraryRoot(
      @Nullable IMediaController caller, int sequenceNumber, @Nullable Bundle libraryParamsBundle)
      throws RuntimeException {
    if (caller == null) {
      return;
    }
    @Nullable
    LibraryParams libraryParams =
        libraryParamsBundle == null ? null : LibraryParams.CREATOR.fromBundle(libraryParamsBundle);
    dispatchSessionTaskWithSessionCommand(
        caller,
        sequenceNumber,
        COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT,
        sendLibraryResultWhenReady(
            (librarySessionImpl, controller, sequenceNum) ->
                librarySessionImpl.onGetLibraryRootOnHandler(controller, libraryParams)));
  }

  @Override
  public void getItem(
      @Nullable IMediaController caller, int sequenceNumber, @Nullable String mediaId)
      throws RuntimeException {
    if (caller == null) {
      return;
    }
    if (TextUtils.isEmpty(mediaId)) {
      Log.w(TAG, "getItem(): Ignoring empty mediaId");
      return;
    }
    dispatchSessionTaskWithSessionCommand(
        caller,
        sequenceNumber,
        COMMAND_CODE_LIBRARY_GET_ITEM,
        sendLibraryResultWhenReady(
            (librarySessionImpl, controller, sequenceNum) ->
                librarySessionImpl.onGetItemOnHandler(controller, mediaId)));
  }

  @Override
  public void getChildren(
      @Nullable IMediaController caller,
      int sequenceNumber,
      String parentId,
      int page,
      int pageSize,
      @Nullable Bundle libraryParamsBundle)
      throws RuntimeException {
    if (caller == null) {
      return;
    }
    if (TextUtils.isEmpty(parentId)) {
      Log.w(TAG, "getChildren(): Ignoring empty parentId");
      return;
    }
    if (page < 0) {
      Log.w(TAG, "getChildren(): Ignoring negative page");
      return;
    }
    if (pageSize < 1) {
      Log.w(TAG, "getChildren(): Ignoring pageSize less than 1");
      return;
    }
    @Nullable
    LibraryParams libraryParams =
        libraryParamsBundle == null ? null : LibraryParams.CREATOR.fromBundle(libraryParamsBundle);
    dispatchSessionTaskWithSessionCommand(
        caller,
        sequenceNumber,
        COMMAND_CODE_LIBRARY_GET_CHILDREN,
        sendLibraryResultWhenReady(
            (librarySessionImpl, controller, sequenceNum) ->
                librarySessionImpl.onGetChildrenOnHandler(
                    controller, parentId, page, pageSize, libraryParams)));
  }

  @Override
  public void search(
      @Nullable IMediaController caller,
      int sequenceNumber,
      String query,
      @Nullable Bundle libraryParamsBundle) {
    if (caller == null) {
      return;
    }
    if (TextUtils.isEmpty(query)) {
      Log.w(TAG, "search(): Ignoring empty query");
      return;
    }
    @Nullable
    LibraryParams libraryParams =
        libraryParamsBundle == null ? null : LibraryParams.CREATOR.fromBundle(libraryParamsBundle);
    dispatchSessionTaskWithSessionCommand(
        caller,
        sequenceNumber,
        COMMAND_CODE_LIBRARY_SEARCH,
        sendLibraryResultWhenReady(
            (librarySessionImpl, controller, sequenceNum) ->
                librarySessionImpl.onSearchOnHandler(controller, query, libraryParams)));
  }

  @Override
  public void getSearchResult(
      @Nullable IMediaController caller,
      int sequenceNumber,
      String query,
      int page,
      int pageSize,
      @Nullable Bundle libraryParamsBundle) {
    if (caller == null) {
      return;
    }
    if (TextUtils.isEmpty(query)) {
      Log.w(TAG, "getSearchResult(): Ignoring empty query");
      return;
    }
    if (page < 0) {
      Log.w(TAG, "getSearchResult(): Ignoring negative page");
      return;
    }
    if (pageSize < 1) {
      Log.w(TAG, "getSearchResult(): Ignoring pageSize less than 1");
      return;
    }
    @Nullable
    LibraryParams libraryParams =
        libraryParamsBundle == null ? null : LibraryParams.CREATOR.fromBundle(libraryParamsBundle);
    dispatchSessionTaskWithSessionCommand(
        caller,
        sequenceNumber,
        COMMAND_CODE_LIBRARY_GET_SEARCH_RESULT,
        sendLibraryResultWhenReady(
            (librarySessionImpl, controller, sequenceNum) ->
                librarySessionImpl.onGetSearchResultOnHandler(
                    controller, query, page, pageSize, libraryParams)));
  }

  @Override
  public void subscribe(
      @Nullable IMediaController caller,
      int sequenceNumber,
      String parentId,
      @Nullable Bundle libraryParamsBundle) {
    if (caller == null) {
      return;
    }
    if (TextUtils.isEmpty(parentId)) {
      Log.w(TAG, "subscribe(): Ignoring empty parentId");
      return;
    }
    @Nullable
    LibraryParams libraryParams =
        libraryParamsBundle == null ? null : LibraryParams.CREATOR.fromBundle(libraryParamsBundle);
    dispatchSessionTaskWithSessionCommand(
        caller,
        sequenceNumber,
        COMMAND_CODE_LIBRARY_SUBSCRIBE,
        sendLibraryResultWhenReady(
            (librarySessionImpl, controller, sequenceNum) ->
                librarySessionImpl.onSubscribeOnHandler(controller, parentId, libraryParams)));
  }

  @Override
  public void unsubscribe(@Nullable IMediaController caller, int sequenceNumber, String parentId) {
    if (caller == null) {
      return;
    }
    if (TextUtils.isEmpty(parentId)) {
      Log.w(TAG, "unsubscribe(): Ignoring empty parentId");
      return;
    }
    dispatchSessionTaskWithSessionCommand(
        caller,
        sequenceNumber,
        COMMAND_CODE_LIBRARY_UNSUBSCRIBE,
        sendLibraryResultWhenReady(
            (librarySessionImpl, controller, sequenceNum) ->
                librarySessionImpl.onUnsubscribeOnHandler(controller, parentId)));
  }

  /** Common interface for code snippets to handle all incoming commands from the controller. */
  private interface SessionTask<T, K extends MediaSessionImpl> {
    T run(K sessionImpl, ControllerInfo controller, int sequenceNumber);
  }

  private interface MediaItemPlayerTask {
    void run(PlayerWrapper player, List<MediaItem> mediaItems);
  }

  /* package */ static final class Controller2Cb implements ControllerCb {

    private final IMediaController iController;

    public Controller2Cb(IMediaController callback) {
      iController = callback;
    }

    public IBinder getCallbackBinder() {
      return iController.asBinder();
    }

    @Override
    public void onSessionResult(int sequenceNumber, SessionResult result) throws RemoteException {
      iController.onSessionResult(sequenceNumber, result.toBundle());
    }

    @Override
    public void onLibraryResult(int sequenceNumber, LibraryResult<?> result)
        throws RemoteException {
      iController.onLibraryResult(sequenceNumber, result.toBundle());
    }

    @Override
    public void onPlayerInfoChanged(
        int sequenceNumber,
        PlayerInfo playerInfo,
        boolean excludeMediaItems,
        boolean excludeMediaItemsMetadata,
        boolean excludeCues,
        boolean excludeTimeline,
        boolean excludeTracks)
        throws RemoteException {
      iController.onPlayerInfoChanged(
          sequenceNumber,
          playerInfo.toBundle(
              excludeMediaItems,
              excludeMediaItemsMetadata,
              excludeCues,
              excludeTimeline,
              excludeTracks),
          /* isTimelineExcluded= */ excludeTimeline);
    }

    @Override
    public void setCustomLayout(int sequenceNumber, List<CommandButton> layout)
        throws RemoteException {
      iController.onSetCustomLayout(sequenceNumber, BundleableUtil.toBundleList(layout));
    }

    @Override
    public void onAvailableCommandsChangedFromSession(
        int sequenceNumber, SessionCommands sessionCommands, Player.Commands playerCommands)
        throws RemoteException {
      iController.onAvailableCommandsChangedFromSession(
          sequenceNumber, sessionCommands.toBundle(), playerCommands.toBundle());
    }

    @Override
    public void onAvailableCommandsChangedFromPlayer(
        int sequenceNumber, Player.Commands availableCommands) throws RemoteException {
      iController.onAvailableCommandsChangedFromPlayer(
          sequenceNumber, availableCommands.toBundle());
    }

    @Override
    public void sendCustomCommand(int sequenceNumber, SessionCommand command, Bundle args)
        throws RemoteException {
      iController.onCustomCommand(sequenceNumber, command.toBundle(), args);
    }

    @SuppressWarnings("nullness:argument") // params can be null.
    @Override
    public void onChildrenChanged(
        int sequenceNumber, String parentId, int itemCount, @Nullable LibraryParams params)
        throws RemoteException {
      iController.onChildrenChanged(
          sequenceNumber, parentId, itemCount, params == null ? null : params.toBundle());
    }

    @SuppressWarnings("nullness:argument") // params can be null.
    @Override
    public void onSearchResultChanged(
        int sequenceNumber, String query, int itemCount, @Nullable LibraryParams params)
        throws RemoteException {
      iController.onSearchResultChanged(
          sequenceNumber, query, itemCount, params == null ? null : params.toBundle());
    }

    @Override
    public void onDisconnected(int sequenceNumber) throws RemoteException {
      iController.onDisconnected(sequenceNumber);
    }

    @Override
    public void onPeriodicSessionPositionInfoChanged(
        int sequenceNumber, SessionPositionInfo sessionPositionInfo) throws RemoteException {
      iController.onPeriodicSessionPositionInfoChanged(
          sequenceNumber, sessionPositionInfo.toBundle());
    }

    @Override
    public void onRenderedFirstFrame(int sequenceNumber) throws RemoteException {
      iController.onRenderedFirstFrame(sequenceNumber);
    }

    @Override
    public void onSessionExtrasChanged(int sequenceNumber, Bundle sessionExtras)
        throws RemoteException {
      iController.onExtrasChanged(sequenceNumber, sessionExtras);
    }

    @Override
    public int hashCode() {
      return ObjectsCompat.hash(getCallbackBinder());
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      if (this == obj) {
        return true;
      }
      if (obj == null || obj.getClass() != Controller2Cb.class) {
        return false;
      }
      Controller2Cb other = (Controller2Cb) obj;
      return Util.areEqual(getCallbackBinder(), other.getCallbackBinder());
    }
  }
}
