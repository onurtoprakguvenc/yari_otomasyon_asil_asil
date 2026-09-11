package org.example.yari;

import org.example.yari.executor.GeminiProvider;

import java.util.Scanner;

public class MainRunner {

    public static void main(String[] args) {
        System.out.println("=== YARI OTOMASYON - STANDALONE RUNNER BAŞLATILDI ===");

        // GeminiProvider sınıfını başlatır (İçindeki hardcoded anahtarı ve modeli kullanır)
        GeminiProvider provider;
        try {
            provider = new GeminiProvider();
            System.out.println("[SİSTEM] GeminiProvider başarıyla yüklendi.");
        } catch (Exception e) {
            System.err.println("[HATA] Provider başlatılamadı: " + e.getMessage());
            return;
        }

        Scanner scanner = new Scanner(System.in);

        System.out.println("\nTest etmek istediğiniz promptu yazıp Enter'a basın (Çıkmak için 'q'):");
        System.out.print("> ");

        while (scanner.hasNextLine()) {
            String input = scanner.nextLine().trim();
            if ("q".equalsIgnoreCase(input)) {
                System.out.println("Çıkış yapılıyor...");
                break;
            }

            if (input.isEmpty()) {
                System.out.print("> ");
                continue;
            }

            System.out.println("\n[İŞLENİYOR] İstek Gemini'ye iletiliyor...");
            try {
                // Modeli GeminiProvider varsayılanından alır (gemini-3.6-flash)
                String response = provider.execute(input, null);

                System.out.println("\n--- MODEL YANITI ---");
                System.out.println(response);
                System.out.println("---------------------\n");

            } catch (Exception e) {
                System.err.println("\n[HATA OLUŞTU] " + e.getMessage() + "\n");
            }

            System.out.print("Yeni bir prompt girin (Çıkmak için 'q'):\n> ");
        }

        scanner.close();
    }
}