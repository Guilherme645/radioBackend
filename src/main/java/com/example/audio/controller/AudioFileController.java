package com.example.audio.controller;

import com.example.audio.service.AudioCutProgress;
import com.example.audio.service.AudioService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@RequestMapping("/audio")
public class AudioFileController {

    @Autowired
    private AudioService audioService;

    // Endpoint para cortar o áudio
    @PostMapping("/cut/{radioName}/{fileName}")
    public ResponseEntity<?> cutAudio(
            @PathVariable("radioName") String radioName,
            @PathVariable("fileName") String fileName,
            @RequestParam("startSeconds") double startSeconds,
            @RequestParam("durationSeconds") double durationSeconds) {

        try {
            // Valida os parâmetros
            if (startSeconds < 0 || durationSeconds <= 0) {
                return ResponseEntity.badRequest().body("Parâmetros inválidos.");
            }

            // Chama o serviço de corte de áudio, passando a rádio (subpasta) e o nome do arquivo
            String outputFileName = audioService.cutAudioFile(radioName, fileName, startSeconds, durationSeconds);

            // Verifica se o arquivo foi gerado corretamente
            Path outputPath = Paths.get("C:/cortes/" + outputFileName);
            if (!Files.exists(outputPath)) {
                return ResponseEntity.status(404).body("Arquivo cortado não encontrado.");
            }

            // Retorna o arquivo de áudio cortado
            Resource resource = new UrlResource(outputPath.toUri());

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("audio/mpeg"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + outputFileName + "\"")
                    .body(resource);

        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Erro ao processar o áudio: " + e.getMessage());
        }
    }
    @PostMapping("/cut-live-segments")
    public ResponseEntity<Map<String, String>> cutLiveStreamSegments(@RequestParam String streamUrl, @RequestParam String radioName) {
        Map<String, String> response = new HashMap<>();

        // Diretório base onde os arquivos serão salvos
        String baseDirectory = "C:/pastaudios/" + radioName;

        // Cria a subpasta correspondente ao nome da rádio, se não existir
        Path radioDirectory = Paths.get(baseDirectory);
        if (!Files.exists(radioDirectory)) {
            try {
                Files.createDirectories(radioDirectory); // Cria diretórios necessários
            } catch (IOException e) {
                e.printStackTrace();
                response.put("status", "error");
                response.put("message", "Erro ao criar diretório para a rádio: " + radioName);
                return ResponseEntity.status(500).body(response);
            }
        }

        // Caminho para salvar os arquivos cortados
        String outputPath = baseDirectory + "/Segment_%Y%m%d_%H%M%S.mp3";
        int segmentDuration = 300;  // 5 minutos (300 segundos)

        // Comando FFmpeg para transcodificar o áudio para MP3
        String[] command = {
                "ffmpeg",
                "-i", streamUrl,   // URL da stream ao vivo
                "-c:a", "libmp3lame", // Codec de áudio para MP3
                "-b:a", "128k",    // Taxa de bits de 128kbps para o áudio
                "-f", "segment",   // Formato de segmentação
                "-segment_time", String.valueOf(segmentDuration),  // Duração de cada segmento
                "-strftime", "1",  // Usar nome de arquivo com data e hora
                outputPath         // Caminho de saída do arquivo
        };

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true); // Redireciona os erros para o stream padrão
            Process process = processBuilder.start();

            // Lê a saída do processo em tempo real e monitora o progresso
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            double totalDuration = 0;
            double currentTime = 0;

            while ((line = reader.readLine()) != null) {
                // Exibe a saída do processo no console (para debug)
                System.out.println(line);

                // Exemplo de linha que o FFmpeg gera: "time=00:01:23.45 bitrate=..."
                if (line.contains("time=")) {
                    // Extraímos o tempo atual do processamento do áudio
                    String timeStr = line.substring(line.indexOf("time=") + 5, line.indexOf("bitrate=")).trim();
                    currentTime = parseFFmpegTime(timeStr);

                    // Se soubermos a duração total da transmissão (em segundos), podemos calcular o progresso
                    if (totalDuration > 0) {
                        int progress = (int) ((currentTime / totalDuration) * 100);
                        // Enviar o progresso para o cliente, por exemplo, via WebSocket
                        sendProgressToClient(progress);
                    }
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                response.put("status", "error");
                response.put("message", "Erro durante a execução do FFmpeg.");
                return ResponseEntity.status(500).body(response);
            }

            response.put("status", "success");
            response.put("message", "Transmissão ao vivo cortada em segmentos de 5 minutos e salva em " + baseDirectory);
            return ResponseEntity.ok(response);
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            response.put("status", "error");
            response.put("message", "Erro ao cortar a transmissão ao vivo.");
            return ResponseEntity.status(500).body(response);
        }
    }

    // Função auxiliar para analisar o tempo no formato "hh:mm:ss.xx" para segundos
    private double parseFFmpegTime(String timeStr) {
        String[] parts = timeStr.split(":");
        double hours = Double.parseDouble(parts[0]);
        double minutes = Double.parseDouble(parts[1]);
        double seconds = Double.parseDouble(parts[2]);
        return hours * 3600 + minutes * 60 + seconds;
    }

    // Função para enviar o progresso ao cliente (pode ser implementado com WebSocket)
    private void sendProgressToClient(int progress) {
        // Implementar WebSocket ou outra forma de comunicação com o frontend para enviar o progresso
    }

    @GetMapping("/radio")
    public ResponseEntity<List<String>> listRadios() {
        try {
            List<String> radios = audioService.listRadios();
            return ResponseEntity.ok(radios);
        } catch (IOException e) {
            return ResponseEntity.status(500).body(null);
        }
    }
    @GetMapping("/radio/{radioName}/contents")
    public ResponseEntity<Map<String, List<String>>> listContentsFromRadio(@PathVariable String radioName) {
        try {
            Map<String, List<String>> contents = audioService.listContentsFromRadio(radioName);
            return ResponseEntity.ok(contents);
        } catch (IOException e) {
            return ResponseEntity.status(500).body(null);
        }
    }

    // Reproduzir o arquivo de áudio de uma rádio específica
    @GetMapping("/play/{radioName}/{fileName}")
    public ResponseEntity<Resource> playAudio(@PathVariable String radioName, @PathVariable String fileName) {
        try {
            Path filePath = Paths.get("C:/pastaudios").resolve(radioName).resolve(fileName);
            Resource resource = new UrlResource(filePath.toUri());

            if (!resource.exists()) {
                return ResponseEntity.notFound().build();
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(Files.probeContentType(filePath)))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + fileName + "\"")
                    .body(resource);
        } catch (IOException e) {
            return ResponseEntity.status(500).body(null);
        }
    }

    // Reproduzir o arquivo de áudio cortado de uma subpasta dentro da pasta 'cortes'
    @GetMapping("/play-cut/{radioName}/{fileName}")
    public ResponseEntity<Resource> playCutAudio(@PathVariable String radioName, @PathVariable String fileName) {
        try {
            // Caminho base da pasta 'cortes'
            Path basePath = Paths.get("C:/cortes").resolve(radioName).resolve(fileName);
            Resource resource = new UrlResource(basePath.toUri());

            // Verifica se o arquivo existe
            if (!resource.exists()) {
                return ResponseEntity.notFound().build();
            }

            // Retorna o arquivo com o tipo de conteúdo apropriado (por exemplo, audio/mp3)
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(Files.probeContentType(basePath)))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + fileName + "\"")
                    .body(resource);
        } catch (IOException e) {
            return ResponseEntity.status(500).body(null);
        }
    }

    // Endpoint para obter o progresso atual do corte e o nome da rádio
    @GetMapping("/progress")
    public ResponseEntity<Map<String, Object>> getCutProgress(@RequestParam String radioName) {
        int progress = audioService.getCutProgress().getProgress();
        Map<String, Object> response = new HashMap<>();
        response.put("progress", progress);
        response.put("radioName", radioName);

        return ResponseEntity.ok(response);
    }

    // Endpoint para pausar o corte de áudio
    @PostMapping("/pause")
    public ResponseEntity<String> pauseCut() {
        try {
            audioService.pauseCut();
            return ResponseEntity.ok("Corte pausado com sucesso.");
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Erro ao pausar o corte: " + e.getMessage());
        }
    }

    // Endpoint para retomar o corte de áudio
    @PostMapping("/resume")
    public ResponseEntity<String> resumeCut() {
        try {
            audioService.resumeCut();
            return ResponseEntity.ok("Corte retomado com sucesso.");
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Erro ao retomar o corte: " + e.getMessage());
        }
    }

    // Endpoint para cancelar o corte de áudio
    @PostMapping("/cancel")
    public ResponseEntity<String> cancelCut() {
        try {
            audioService.cancelCut();
            return ResponseEntity.ok("Corte cancelado com sucesso.");
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Erro ao cancelar o corte: " + e.getMessage());
        }
    }

    // Método para fazer upload de um arquivo de áudio
    @PostMapping("/upload")
    public ResponseEntity<?> uploadAudio(@RequestParam("file") MultipartFile file) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body("O arquivo de áudio está vazio.");
            }

            String fileName = audioService.saveAudioFile(file);
            Map<String, String> response = new HashMap<>();
            response.put("fileName", fileName);
            return ResponseEntity.ok(response);
        } catch (IOException e) {
            return ResponseEntity.status(500).body("Erro ao carregar o arquivo.");
        }
    }

    // Método para listar todos os arquivos de áudio na pasta audiopasta
    @GetMapping("/list")
    public ResponseEntity<List<String>> listAllAudioFilesInAudiopasta() {
        try {
            List<String> fileNames = audioService.listAudioFilesFromDirectory("C:/pastaudios");
            return ResponseEntity.ok(fileNames);
        } catch (IOException e) {
            return ResponseEntity.status(500).body(null);
        }
    }

    // Método para listar todos os arquivos de áudio na pasta cortes
    // Método para listar todas as subpastas e arquivos de áudio cortados
    @GetMapping("/list/cortes")
    public ResponseEntity<Map<String, List<String>>> listCortes() {
        try {
            // Caminho da pasta de cortes
            Path cortesPath = Paths.get("C:/cortes");

            // Map para armazenar subpastas e seus arquivos
            Map<String, List<String>> cortes = new HashMap<>();

            // Iterar sobre as subpastas (datas) na pasta de cortes
            try (Stream<Path> walk = Files.walk(cortesPath, 1)) {
                walk.filter(Files::isDirectory).forEach(subFolder -> {
                    try {
                        // Listar os arquivos de áudio em cada subpasta (corte por data)
                        List<String> files = Files.walk(subFolder, 1)
                                .filter(Files::isRegularFile)
                                .map(path -> path.getFileName().toString())
                                .collect(Collectors.toList());

                        // Adicionar a subpasta e seus arquivos no map
                        cortes.put(subFolder.getFileName().toString(), files);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
            }

            return ResponseEntity.ok(cortes);
        } catch (IOException e) {
            return ResponseEntity.status(500).body(null);
        }
    }
    @GetMapping("/play/corte/{subFolder}/{fileName}")
    public ResponseEntity<Resource> playCorte(@PathVariable String subFolder, @PathVariable String fileName) {
        try {
            // Caminho completo para o arquivo de áudio dentro da subpasta
            Path filePath = Paths.get("C:/cortes").resolve(subFolder).resolve(fileName);
            Resource resource = new UrlResource(filePath.toUri());

            // Verifica se o arquivo existe
            if (!resource.exists()) {
                return ResponseEntity.notFound().build();
            }

            // Retorna o arquivo de áudio para reprodução
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(Files.probeContentType(filePath)))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + fileName + "\"")
                    .body(resource);
        } catch (IOException e) {
            return ResponseEntity.status(500).body(null);
        }
    }

    // Método para fazer o download de um arquivo de áudio específico
    @GetMapping("/download/{fileName}")
    public ResponseEntity<Resource> downloadAudio(@PathVariable String fileName) {
        try {
            Path filePath = Paths.get("C:/pastaudios").resolve(fileName).normalize();
            Resource resource = new UrlResource(filePath.toUri());

            if (!resource.exists()) {
                return ResponseEntity.notFound().build();
            }

            // Ajustar o cabeçalho para "inline" para permitir a reprodução no navegador
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("audio/mpeg"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + fileName + "\"")
                    .body(resource);
        } catch (MalformedURLException e) {
            return ResponseEntity.status(500).body(null);
        }
    }
}