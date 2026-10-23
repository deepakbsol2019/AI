package com.ai.service.impl;

import org.springframework.ai.audio.transcription.AudioTranscriptionOptions;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.openai.OpenAiAudioTranscriptionOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import com.ai.service.MyTranscriptService;
@Service
public class MyTranscriptServiceImpl implements MyTranscriptService{

	@Value("classpath:test.wav")
	private Resource audioFile;
	AudioTranscriptionOptions option=OpenAiAudioTranscriptionOptions.builder().language("HINDI").build();
	private final TranscriptionModel transcriptionModel;
	public MyTranscriptServiceImpl(TranscriptionModel transcriptionModel) {
		this.transcriptionModel=transcriptionModel;
	}
	@Override
	public String converAudioToText() {
		return transcriptionModel.transcribe(audioFile);
	}

}
