package com.example.audio.service;

import com.example.audio.model.AudioFile;
import com.example.audio.repository.AudioFileRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class AudioService {

    private final String UPLOAD_DIR = "C:/pastaudios";
    private final String FFMPEG_PATH = "C:/ffmpeg/ffmpeg-7.0.2-full_build/bin/ffmpeg.exe";
    private final String OUTPUT_DIR = "C:/cortes";

    @Autowired
    private AudioFileRepository audioFileRepository;

    private volatile boolean isPaused = false;
    private volatile boolean isCancelled = false;
    private Process ffmpegProcess;
    private Thread cuttingThread;
    private final Path rootLocation = Paths.get("C:/pastaudios");

    // Listar todas as subpastas (rádios)
    public List<String> listRadios() throws IOException {
        try (Stream<Path> walk = Files.walk(rootLocation, 1)) {
            return walk.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toList());
        }
    }

    // Listar arquivos dentro de uma rádio específica (subpasta)
    public Map<String, List<String>> listContentsFromRadio(String radioName) throws IOException {
        Path radioPath = rootLocation.resolve(radioName);
        if (!Files.exists(radioPath) || !Files.isDirectory(radioPath)) {
            throw new IOException("Rádio não encontrada: " + radioName);
        }

        Map<String, List<String>> contents = new HashMap<>();
        List<String> files = Files.walk(radioPath, 1)
                .filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .collect(Collectors.toList());
        contents.put("files", files);
        return contents;
    }

    private AudioCutProgress cutProgress = new AudioCutProgress();

    // Função para calcular a duração do arquivo de áudio usando FFmpeg
    public long getAudioDuration(String filePath) throws IOException {
        String[] command = {FFMPEG_PATH, "-i", filePath};

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("Duration")) {
                    String[] parts = line.split(",")[0].split(":");
                    String[] timeParts = parts[1].trim().split(":");
                    long hours = Long.parseLong(timeParts[0].trim());
                    long minutes = Long.parseLong(timeParts[1].trim());
                    float seconds = Float.parseFloat(timeParts[2].trim());

                    long totalSeconds = (hours * 3600) + (minutes * 60) + (long) seconds;
                    return totalSeconds;
                }
            }
        }

        try {
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("O processo foi interrompido", e);
        }
        return 0;
    }

    // Salva o arquivo de áudio no servidor e no banco de dados
    public String saveAudioFile(MultipartFile file) throws IOException {
        Path uploadPath = Paths.get(UPLOAD_DIR);
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }

        String filePath = UPLOAD_DIR + "/" + file.getOriginalFilename();
        File dest = new File(filePath);
        file.transferTo(dest);

        AudioFile audioFile = new AudioFile();
        audioFile.setFileName(file.getOriginalFilename());
        audioFile.setFilePath(filePath);
        audioFile.setSize(file.getSize());

        long duration = getAudioDuration(filePath);
        audioFile.setDuration(duration);

        audioFileRepository.save(audioFile);

        return file.getOriginalFilename();
    }

    // Lista todos os arquivos de áudio em um diretório específico
    public List<String> listAudioFilesFromDirectory(String directory) throws IOException {
        Path dirPath = Paths.get(directory);
        if (!Files.exists(dirPath)) {
            throw new IOException("Diretório não encontrado: " + directory);
        }
        return Files.walk(dirPath)
                .filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .collect(Collectors.toList());
    }

    // Lista todos os arquivos de áudio no diretório de uploads
    public List<String> listAudioFilesFromUploads() throws IOException {
        return listAudioFilesFromDirectory(UPLOAD_DIR);
    }

    // Lista todos os arquivos de áudio no diretório de cortes
    public List<String> listAudioFilesFromCortes() throws IOException {
        return listAudioFilesFromDirectory(OUTPUT_DIR);
    }

    // Função para cortar um arquivo de áudio e atualizar o progresso
    public String cutAudioFile(String radioName, String fileName, double start, double duration) throws IOException {
        // Reseta o estado de pausa/cancelamento
        resetCutState();

        // Construa o caminho completo do arquivo de entrada (incluindo a subpasta da rádio)
        String inputFilePath = Paths.get(UPLOAD_DIR, radioName, fileName).toString();

        // Obter a data atual no formato yyyy-MM-dd
        String currentDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

        // Criar o diretório com a data dentro de OUTPUT_DIR (se ainda não existir)
        Path dateDirectory = Paths.get(OUTPUT_DIR, currentDate);
        if (!Files.exists(dateDirectory)) {
            Files.createDirectories(dateDirectory); // Cria o diretório
        }

        // Construa o caminho completo do arquivo de saída dentro do diretório de data
        String outputFilePath = Paths.get(dateDirectory.toString(), "cortes_" + fileName).toString();

        // Comando FFmpeg para corte
        String[] command = {
                FFMPEG_PATH,
                "-ss", String.valueOf(start),
                "-i", inputFilePath,
                "-t", String.valueOf(duration),
                "-c", "copy",  // Cópia sem reprocessamento
                outputFilePath
        };

        // Log para verificar o comando gerado
        System.out.println("Comando FFmpeg: " + String.join(" ", command));

        AtomicInteger progressStep = new AtomicInteger(0);

        cuttingThread = new Thread(() -> {
            try {
                ProcessBuilder processBuilder = new ProcessBuilder(command);
                processBuilder.redirectErrorStream(true);
                ffmpegProcess = processBuilder.start();

                // Inicializa o progresso
                cutProgress.setProgress(0);

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(ffmpegProcess.getInputStream()))) {
                    String line;

                    while ((line = reader.readLine()) != null) {
                        System.out.println(line);

                        synchronized (this) {
                            // Pausa o corte se estiver pausado
                            while (isPaused) {
                                try {
                                    wait();
                                } catch (InterruptedException e) {
                                    System.out.println("Corte interrompido.");
                                    return;
                                }
                            }
                        }

                        // Checa se o processo foi cancelado
                        if (isCancelled) {
                            ffmpegProcess.destroy();
                            return;
                        }

                        // Atualiza o progresso em intervalos simulados
                        progressStep.addAndGet(10);
                        cutProgress.setProgress(progressStep.get());
                    }

                    int exitCode = ffmpegProcess.waitFor();
                    if (exitCode != 0) {
                        throw new IOException("Erro no processo FFmpeg, código de saída: " + exitCode);
                    }

                    // Concluir o progresso
                    cutProgress.setProgress(100);

                } catch (Exception e) {
                    e.printStackTrace();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });

        cuttingThread.start();
        try {
            cuttingThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("Corte interrompido.");
        }

        // Retornar o nome do arquivo cortado junto com o diretório da data
        return Paths.get(currentDate, "cortes_" + fileName).toString();
    }

    // Retorna o progresso do corte
    public AudioCutProgress getCutProgress() {
        return cutProgress;
    }

    // Método para pausar o corte
    public synchronized void pauseCut() {
        isPaused = true;
        System.out.println("Corte pausado.");
    }

    // Método para retomar o corte
    public synchronized void resumeCut() {
        isPaused = false;
        notify();
        System.out.println("Corte retomado.");
    }

    // Método para cancelar o corte
    public void cancelCut() {
        isCancelled = true;
        if (ffmpegProcess != null) {
            ffmpegProcess.destroy();
        }
    }

    // Método para resetar o estado de pausa e cancelamento após cada corte
    public void resetCutState() {
        isPaused = false;
        isCancelled = false;
    }
}