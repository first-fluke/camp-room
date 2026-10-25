package camp.room.app.di

import android.content.Context
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.Mode as SttMode
import camp.room.pleos.MotionProvider
import camp.room.pleos.MotionState
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ViewModelScoped

/** Hilt owns SDK creation; lifecycle adapters own initialize/stop/release. */
@Module
@InstallIn(ViewModelComponent::class)
object PleosSdkModule {
    @Provides @ViewModelScoped fun vehicle(@ApplicationContext context:Context):Vehicle=Vehicle(context)
    @Provides @ViewModelScoped fun fusedLocation(@ApplicationContext context:Context):FusedLocation=FusedLocation(context)
    @Provides @ViewModelScoped fun motion():MotionProvider=MotionProvider{MotionState.UNKNOWN}
    // Unscoped Provider factories intentionally create fresh sessions after timeout/close.
    @Provides fun navigation(@ApplicationContext context:Context):NaviHelper=NaviHelper(context)
    @Provides fun speech(@ApplicationContext context:Context):TextToSpeech=TextToSpeech(context,TtsMode.ON_DEVICE)
    @Provides fun recognition(@ApplicationContext context:Context):SpeechToText=SpeechToText(context,SttMode.ON_DEVICE)
}
