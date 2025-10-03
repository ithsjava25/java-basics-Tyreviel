package com.example.api;


import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.Comparator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.Map;


@Command(name = "elpris", mixinStandardHelpOptions = true, version = "1.0",
        description = "CLI för elprisetjustnu.se")
public class ElprisCLI implements Runnable {

    @Option(names = "--date", description = "Datum i formatet YYYY-MM-DD")
    private Optional<String> date = Optional.empty();

    @Option(names = "--zone", description = "Elområde: SE1, SE2, SE3, SE4")
    private String zone;

    @Option(names = "--sorted", description = "Sortera priser i fallande ordning", defaultValue = "false")
    private boolean sorted;


    @Option(names = "--charging", description = "Optimera laddning för antal timmar (2, 4 eller 8)")
    private String chargingHours;

    public Integer getChargingHours() {
        if (chargingHours == null) return null;
        return Integer.parseInt(chargingHours.replace("h", ""));
    }



    @Override

    public void run() {
        if (zone == null && date.isEmpty() && chargingHours == null) {
            System.out.println("Usage: elpris --zone=<SE1-SE4> [--date=YYYY-MM-DD] [--sorted] [--charging=2|4|8]");
            return;
        }
        if (zone == null || zone.isBlank()) {
            System.out.println("Missing required option: --zone");
            System.out.println("Fel: --zone är obligatorisk (required zone argument)");
            return;
        }

        ElpriserAPI.Prisklass klass;
        try {
            klass = ElpriserAPI.Prisklass.valueOf(zone);
        } catch (IllegalArgumentException e) {
            System.out.println("Fel: Ogiltig zon '" + zone + "'. Välj mellan SE1, SE2, SE3, SE4. (invalid zone)");
            return;
        }
        LocalDate datum;
        try{
         datum = date.map(LocalDate::parse).orElse(LocalDate.now());
        } catch (DateTimeParseException e) {
            System.out.println("Ogiltigt datum: " + date.orElse(""));
            return;
        }



        List<ElpriserAPI.Elpris> priser = hämtaAllaPriser(datum, klass);
        if (priser.isEmpty()) {
            System.out.println("Inga priser hittades.");
            return;
        }


        List<Map.Entry<LocalDateTime, Double>> timpriser = beräknaTimpriser(priser);
        timpriser = sorted
                ? sorteraFallandeMedTid(timpriser)
                : timpriser.stream().sorted(Map.Entry.comparingByKey()).collect(Collectors.toList());

        skrivUtTimpriser(timpriser);


        skrivUtStatistik(priser, datum);
        Integer timmar = getChargingHours();
        if (timmar != null) {
            skrivUtBilligasteLaddning(priser, timmar);
        }
    }
    public List<ElpriserAPI.Elpris> hämtaAllaPriser(LocalDate datum, ElpriserAPI.Prisklass klass) {
        List<ElpriserAPI.Elpris> priser = new ArrayList<>();

        List<ElpriserAPI.Elpris> idag = api.getPriser(datum, klass);
        if (idag != null) priser.addAll(idag);

        if (LocalTime.now().isAfter(LocalTime.of(13, 0))) {
            List<ElpriserAPI.Elpris> imorgon = api.getPriser(datum.plusDays(1), klass);
            if (imorgon != null) priser.addAll(imorgon);
        }

        return priser;
    }


    public List<ElpriserAPI.Elpris> sorteraPriser(List<ElpriserAPI.Elpris> priser, boolean fallande) {
        if (!fallande) {
            return priser; // behåll API-ordningen
        }

        return priser.stream()
                .sorted(Comparator.comparingDouble(ElpriserAPI.Elpris::sekPerKWh).reversed())
                .collect(Collectors.toList());
    }
    public static List<Map.Entry<LocalDateTime, Double>> sorteraFallandeMedTid(List<Map.Entry<LocalDateTime, Double>> lista) {
        return lista.stream()
                .sorted((a, b) -> {
                    int prisJämf = Double.compare(b.getValue(), a.getValue());
                    if (prisJämf != 0) return prisJämf;
                    return a.getKey().compareTo(b.getKey());
                })
                .collect(Collectors.toList());
    }

    public void skrivUtTimpriser(List<Map.Entry<LocalDateTime, Double>> timpriser) {
        System.out.println("\nElpriser per timme:");
        for (var entry : timpriser) {
            String start = String.format("%02d", entry.getKey().getHour());
            String end = String.format("%02d", entry.getKey().plusHours(1).getHour());
            String tid = start + "-" + end;
            String pris = String.format("%.2f", entry.getValue() * 100).replace('.', ',');
            System.out.println(tid + " " + pris + " öre");
        }
    }

    public void skrivUtStatistik(List<ElpriserAPI.Elpris> priser, LocalDate datum) {
        List<Map.Entry<LocalDateTime, Double>> timpriser = beräknaTimpriser(priser);
        double total = 0;
        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;
       LocalDateTime billigast = null;
       LocalDateTime dyrast = null;

        for (var entry : timpriser) {
            double pris = entry.getValue();
            total += pris;
            if (pris < min) { min = pris; billigast = entry.getKey(); }
            if (pris > max) { max = pris; dyrast = entry.getKey(); }
        }
        if (billigast == null || dyrast == null) {
            System.out.println("Ingen giltig prisdata hittades");
            return;
        }

        double snitt = total / timpriser.size();
        Set<LocalDate> datumSet = priser.stream()
                .map(p -> p.timeStart().toLocalDate())
                .collect(Collectors.toCollection(TreeSet::new));

        System.out.println("\nPrisstatistik för: " + String.join(", ", datumSet.stream().map(LocalDate::toString).toList()));
        System.out.printf("Medelpris: %.4f SEK/kWh\n", snitt);
        String start = String.format("%02d", billigast.getHour());
        String slut = String.format("%02d", billigast.plusHours(1).getHour());
        System.out.printf("Lägsta pris: %.4f SEK/kWh kl %s-%s\n", min, start, slut);

        System.out.printf("Högsta pris: %.4f SEK/kWh kl %s\n", max,
                dyrast.toLocalDate() + " kl " + dyrast.toLocalTime());


    }
    public void skrivUtBilligasteLaddning(List<ElpriserAPI.Elpris> kvartpriser, int timmar) {
        if (timmar != 2 && timmar != 4 && timmar != 8) {
            throw new IllegalArgumentException("Ogiltigt värde för --charging. Välj 2, 4 eller 8.");
        }

        List<Map.Entry<LocalDateTime, Double>> timpriser = beräknaTimpriser(kvartpriser);
        if (timpriser.size() < timmar) {
            System.out.println("Påbörja laddning: För få timpriser tillgängliga för att optimera laddning (" + timmar + "h).");
            return;
        }
        timpriser = timpriser.stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toList());

        double minSum = Double.MAX_VALUE;
        int startIndex = 0;

        for (int i = 0; i <= timpriser.size() - timmar; i++) {
            double sum = 0;
            for (int j = 0; j < timmar; j++) {
                sum += timpriser.get(i + j).getValue();
            }
            if (sum < minSum) {
                minSum = sum;
                startIndex = i;
            }
        }

        List<Map.Entry<LocalDateTime, Double>> fönster = timpriser.subList(startIndex, startIndex + timmar);


        System.out.println("Påbörja laddning under följande timmar:");
        for (var entry : fönster) {
            String tid = "kl " + String.format("%02d:00", entry.getKey().getHour());
            String pris = String.format("%.4f", entry.getValue()).replace('.', ',');
            System.out.printf("%s → %s SEK/kWh\n", tid, pris);
        }

        System.out.printf("Totalt: %.2f öre\n", minSum * 100);
        double medel = minSum / timmar;
        System.out.printf("Medelpris för fönster: %s öre\n", String.format("%.2f", medel * 100).replace('.', ','));
    }

    public static List<Map.Entry<LocalDateTime, Double>> beräknaTimpriser(List<ElpriserAPI.Elpris> kvartpriser) {
        // Runda ner till hel timme (inklusive sekunder och nanos)
        Map<LocalDateTime, List<ElpriserAPI.Elpris>> grupperade = kvartpriser.stream()
                .collect(Collectors.groupingBy(p ->
                        p.timeStart()
                                .withMinute(0)
                                .withSecond(0)
                                .withNano(0).toLocalDateTime()
                ));

        grupperade.entrySet().removeIf(entry -> entry.getValue().isEmpty());

        // Beräkna snittpris per timme
        return grupperade.entrySet().stream()
                .map(entry -> Map.entry(
                        entry.getKey(),
                        entry.getValue().stream()
                                .mapToDouble(ElpriserAPI.Elpris::sekPerKWh)
                                .average().orElse(0)
                ))
                .collect(Collectors.toList());
    }



    private final ElpriserAPI api;
    public ElprisCLI(ElpriserAPI api){
        this.api = api;
    }
    public static void runInteractive() {
        Scanner scanner = new Scanner(System.in);
        String zone = "";
        while (true) {
            System.out.println(" Välj elområde (SE1, SE2, SE3, SE4):");
            zone = scanner.nextLine().trim().toUpperCase();

            if (zone.matches("SE[1-4]")) {
                break;
            } else {
                System.out.println("Ogiltigt elområde. Ange SE1, SE2, SE3 eller SE4.");
            }
        }


        System.out.println("Ange datum (YYYY-MM-DD), eller lämna tomt för idag:");
        String dateInput = scanner.nextLine().trim();
        LocalDate date = dateInput.isEmpty() ? LocalDate.now() : LocalDate.parse(dateInput);

        System.out.println("Vill du sortera priser i fallande ordning? (ja/nej):");
        boolean sorted = scanner.nextLine().trim().equalsIgnoreCase("ja");

        System.out.println("Vill du optimera laddning? Ange antal timmar (2, 4, 8), eller lämna tomt:");
        String chargingInput = scanner.nextLine().trim();
        Integer charging = chargingInput.isEmpty() ? null : Integer.parseInt(chargingInput);

        // Kör CLI-logik direkt
        ElprisCLI cli = new ElprisCLI(new ElpriserAPI());
        cli.zone = zone;
        cli.date = Optional.of(date.toString());
        cli.sorted = sorted;
        cli.chargingHours = charging + "h";
        cli.run();
    }





}
