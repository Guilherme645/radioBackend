package com.example.audio.service;

public class AudioCutProgress {
    private int progress;  // Progresso em porcentagem (0 a 100)

    // Construtor
    public AudioCutProgress() {
        this.progress = 0;
    }

    // Getter para o progresso
    public int getProgress() {
        return progress;
    }

    // Setter para o progresso
    public void setProgress(int progress) {
        this.progress = progress;
    }
}