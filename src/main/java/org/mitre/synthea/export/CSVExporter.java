package org.mitre.synthea.export;

import static org.mitre.synthea.export.ExportHelper.dateFromTimestamp;
import static org.mitre.synthea.export.ExportHelper.iso8601Timestamp;

import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

import org.mitre.synthea.helpers.Utilities;
import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.concepts.HealthRecord;
import org.mitre.synthea.world.concepts.HealthRecord.CarePlan;
import org.mitre.synthea.world.concepts.HealthRecord.Code;
import org.mitre.synthea.world.concepts.HealthRecord.Encounter;
import org.mitre.synthea.world.concepts.HealthRecord.Entry;
import org.mitre.synthea.world.concepts.HealthRecord.ImagingStudy;
import org.mitre.synthea.world.concepts.HealthRecord.Medication;
import org.mitre.synthea.world.concepts.HealthRecord.Observation;
import org.mitre.synthea.world.concepts.HealthRecord.Procedure;

/**
 * Researchers have requested a simple table-based format
 * that could easily be imported into any database for analysis.
 * Unlike other formats which export a single record per patient,
 * this format generates 9 total files,
 * and adds lines to each based on the clinical events for each patient.
 * These files are intended to be analogous to database tables,
 * with the patient UUID being a foreign key.
 * Files include:
 * patients.csv, encounters.csv, allergies.csv,
 * medications.csv, conditions.csv, careplans.csv,
 * observations.csv, procedures.csv, and immunizations.csv.
 */
public class CSVExporter {
  /**
   * Writer for patients.csv.
   */
  private FileWriter patients;
  /**
   * Writer for allergies.csv.
   */
  private FileWriter allergies;
  /**
   * Writer for medications.csv.
   */
  private FileWriter medications;
  /**
   * Writer for conditions.csv.
   */
  private FileWriter conditions;
  /**
   * Writer for careplans.csv.
   */
  private FileWriter careplans;
  /**
   * Writer for observations.csv.
   */
  private FileWriter observations;
  /**
   * Writer for procedures.csv.
   */
  private FileWriter procedures;
  /**
   * Writer for immunizations.csv.
   */
  private FileWriter immunizations;
  /**
   * Writer for encounters.csv.
   */
  private FileWriter encounters;
  /**
   * Writer for imaging_studies.csv
   */
  private FileWriter imagingStudies;

  /**
   * System-dependent string for a line break. (\n on Mac, *nix, \r\n on Windows)
   */
  private static final String NEWLINE = System.lineSeparator();

  /**
   * Constructor for the CSVExporter -
   *  initialize the 9 specified files and store the writers in fields.
   */
  private CSVExporter() {
    try {
      File output = Exporter.getOutputFolder("csv", null);
      output.mkdirs();
      Path outputDirectory = output.toPath();
      File patientsFile = outputDirectory.resolve("patients.csv").toFile();
      File allergiesFile = outputDirectory.resolve("allergies.csv").toFile();
      File medicationsFile = outputDirectory.resolve("medications.csv").toFile();
      File conditionsFile = outputDirectory.resolve("conditions.csv").toFile();
      File careplansFile = outputDirectory.resolve("careplans.csv").toFile();
      File observationsFile = outputDirectory.resolve("observations.csv").toFile();
      File proceduresFile = outputDirectory.resolve("procedures.csv").toFile();
      File immunizationsFile = outputDirectory.resolve("immunizations.csv").toFile();
      File encountersFile = outputDirectory.resolve("encounters.csv").toFile();
      File imagingStudiesFile = outputDirectory.resolve("imaging_studies.csv").toFile();

      patients = new FileWriter(patientsFile);
      allergies = new FileWriter(allergiesFile);
      medications = new FileWriter(medicationsFile);
      conditions = new FileWriter(conditionsFile);
      careplans = new FileWriter(careplansFile);
      observations = new FileWriter(observationsFile);
      procedures = new FileWriter(proceduresFile);
      immunizations = new FileWriter(immunizationsFile);
      encounters = new FileWriter(encountersFile);
      imagingStudies = new FileWriter(imagingStudiesFile);
      writeCSVHeaders();
    } catch (IOException e) {
      // wrap the exception in a runtime exception.
      // the singleton pattern below doesn't work if the constructor can throw
      // and if these do throw ioexceptions there's nothing we can do anyway
      throw new RuntimeException(e);
    }
  }

  /**
   * Write the headers to each of the CSV files.
   * @throws IOException if any IO error occurs
   */
  private void writeCSVHeaders() throws IOException {
    patients.write("ID,BIRTHDATE,DEATHDATE,SSN,DRIVERS,PASSPORT,"
        + "PREFIX,FIRST,LAST,SUFFIX,MAIDEN,MARITAL,RACE,ETHNICITY,GENDER,BIRTHPLACE,"
        + "ADDRESS,CITY,STATE,ZIP");
    patients.write(NEWLINE);
    allergies.write("START,STOP,PATIENT,ENCOUNTER,CODE,DESCRIPTION");
    allergies.write(NEWLINE);
    medications.write(
        "START,STOP,PATIENT,ENCOUNTER,CODE,DESCRIPTION,COST,DISPENSES,TOTALCOST,"
        + "REASONCODE,REASONDESCRIPTION"
    );
    medications.write(NEWLINE);
    conditions.write("START,STOP,PATIENT,ENCOUNTER,CODE,DESCRIPTION");
    conditions.write(NEWLINE);
    careplans.write(
        "ID,START,STOP,PATIENT,ENCOUNTER,CODE,DESCRIPTION,REASONCODE,REASONDESCRIPTION");
    careplans.write(NEWLINE);
    observations.write("DATE,PATIENT,ENCOUNTER,CODE,DESCRIPTION,VALUE,UNITS,TYPE");
    observations.write(NEWLINE);
    procedures.write("DATE,PATIENT,ENCOUNTER,CODE,DESCRIPTION,COST,REASONCODE,REASONDESCRIPTION");
    procedures.write(NEWLINE);
    immunizations.write("DATE,PATIENT,ENCOUNTER,CODE,DESCRIPTION,COST");
    immunizations.write(NEWLINE);
    encounters.write("ID,START,STOP,PATIENT,ENCOUNTERCLASS,CODE,DESCRIPTION,COST,"
        + "REASONCODE,REASONDESCRIPTION");
    encounters.write(NEWLINE);
    imagingStudies.write("ID,DATE,PATIENT,ENCOUNTER,BODYSITE_CODE,BODYSITE_DESCRIPTION,"
        + "MODALITY_CODE,MODALITY_DESCRIPTION,SOP_CODE,SOP_DESCRIPTION");
    imagingStudies.write(NEWLINE);
  }

  /**
   *  Thread safe singleton pattern adopted from
   *  https://stackoverflow.com/questions/7048198/thread-safe-singletons-in-java
   */
  private static class SingletonHolder {
    /**
     * Singleton instance of the CSVExporter.
     */
    private static final CSVExporter instance = new CSVExporter();
  }

  /**
   * Get the current instance of the CSVExporter.
   * @return the current instance of the CSVExporter.
   */
  public static CSVExporter getInstance() {
    return SingletonHolder.instance;
  }

  /**
   * Add a single Person's health record info to the CSV records.
   * @param person Person to write record data for
   * @param time Time the simulation ended
   * @throws IOException if any IO error occurs
   */
  public void export(Person person, long time) throws IOException {
    String personID = patient(person, time);

    for (Encounter encounter : person.record.encounters) {
      String encounterID = encounter(personID, encounter);

      for (HealthRecord.Entry condition : encounter.conditions) {
        condition(personID, encounterID, condition);
      }

      for (HealthRecord.Entry allergy : encounter.allergies) {
        allergy(personID, encounterID, allergy);
      }

      for (Observation observation : encounter.observations) {
        observation(personID, encounterID, observation);
      }

      for (Procedure procedure : encounter.procedures) {
        procedure(personID, encounterID, procedure);
      }

      for (Medication medication : encounter.medications) {
        medication(personID, encounterID, medication, time);
      }

      for (HealthRecord.Entry immunization : encounter.immunizations) {
        immunization(personID, encounterID, immunization);
      }

      for (CarePlan careplan : encounter.careplans) {
        careplan(personID, encounterID, careplan);
      }

      for (ImagingStudy imagingStudy : encounter.imagingStudies) {
        imagingStudy(personID, encounterID, imagingStudy);
      }
    }

    patients.flush();
    encounters.flush();
    conditions.flush();
    allergies.flush();
    medications.flush();
    careplans.flush();
    observations.flush();
    procedures.flush();
    immunizations.flush();
    imagingStudies.flush();
  }

  /**
   * Write a single Patient line, to patients.csv.
   *
   * @param person Person to write data for
   * @param time Time the simulation ended, to calculate age/deceased status
   * @return the patient's ID, to be referenced as a "foreign key" if necessary
   * @throws IOException if any IO error occurs
   */
  private String patient(Person person, long time) throws IOException {
    // ID,BIRTHDATE,DEATHDATE,SSN,DRIVERS,PASSPORT,PREFIX,
    // FIRST,LAST,SUFFIX,MAIDEN,MARITAL,RACE,ETHNICITY,GENDER,BIRTHPLACE,ADDRESS
    StringBuilder s = new StringBuilder();

    String personID = (String) person.attributes.get(Person.ID);
    s.append(personID).append(',');
    s.append(dateFromTimestamp((long)person.attributes.get(Person.BIRTHDATE))).append(',');
    if (!person.alive(time)) {
      s.append(dateFromTimestamp(person.record.death));
    }

    for (String attribute : new String[] {
        Person.IDENTIFIER_SSN,
        Person.IDENTIFIER_DRIVERS,
        Person.IDENTIFIER_PASSPORT,
        Person.NAME_PREFIX,
        Person.FIRST_NAME,
        Person.LAST_NAME,
        Person.NAME_SUFFIX,
        Person.MAIDEN_NAME,
        Person.MARITAL_STATUS,
        Person.RACE,
        Person.ETHNICITY,
        Person.GENDER,
        Person.BIRTHPLACE,
        Person.ADDRESS,
        Person.CITY,
        Person.STATE,
        Person.ZIP
    }) {
      String value = (String) person.attributes.getOrDefault(attribute, "");
      s.append(',').append(clean(value));
    }

    s.append(NEWLINE);
    write(s.toString(), patients);

    return personID;
  }

  /**
   * Write a single Encounter line to encounters.csv.
   *
   * @param personID The ID of the person that had this encounter
   * @param encounter The encounter itself
   * @return The encounter ID, to be referenced as a "foreign key" if necessary
   * @throws IOException if any IO error occurs
   */
  private String encounter(String personID, Encounter encounter) throws IOException {
    // ID,START,STOP,PATIENT,ENCOUNTERCLASS,CODE,DESCRIPTION,COST,REASONCODE,REASONDESCRIPTION
    StringBuilder s = new StringBuilder();

    String encounterID = UUID.randomUUID().toString();
    //ID
    s.append(encounterID).append(',');
    //START
    s.append(iso8601Timestamp(encounter.start)).append(',');
    //STOP
    if (encounter.stop != 0L) {
      s.append(iso8601Timestamp(encounter.stop)).append(',');
    } else {
      s.append(',');
    }
    //PATIENT
    s.append(personID).append(',');

    //ENCOUNTERCLASS
    if (encounter.type != null) {
      s.append(encounter.type.toLowerCase()).append(',');
    } else {
      s.append(',');
    }
    //CODE
    Code coding = encounter.codes.get(0);
    s.append(coding.code).append(',');
    //DESCRIPTION
    s.append(clean(coding.display)).append(',');
    //COST
    s.append(String.format(Locale.US, "%.2f", encounter.cost())).append(',');
    //REASONCODE & REASONDESCRIPTION
    if (encounter.reason == null) {
      s.append(",");
    } else {
      s.append(encounter.reason.code).append(',');
      s.append(clean(encounter.reason.display));
    }

    s.append(NEWLINE);
    write(s.toString(), encounters);

    return encounterID;
  }

  /**
   * Write a single Condition to conditions.csv.
   *
   * @param personID ID of the person that has the condition.
   * @param encounterID ID of the encounter where the condition was diagnosed
   * @param condition The condition itself
   * @throws IOException if any IO error occurs
   */
  private void condition(String personID, String encounterID,
      Entry condition) throws IOException {
    // START,STOP,PATIENT,ENCOUNTER,CODE,DESCRIPTION
    StringBuilder s = new StringBuilder();

    s.append(dateFromTimestamp(condition.start)).append(',');
    if (condition.stop != 0L) {
      s.append(dateFromTimestamp(condition.stop));
    }
    s.append(',');
    s.append(personID).append(',');
    s.append(encounterID).append(',');

    Code coding = condition.codes.get(0);

    s.append(coding.code).append(',');
    s.append(clean(coding.display));

    s.append(NEWLINE);
    write(s.toString(), conditions);
  }

  /**
   * Write a single Allergy to allergies.csv.
   *
   * @param personID ID of the person that has the allergy.
   * @param encounterID ID of the encounter where the allergy was diagnosed
   * @param allergy The allergy itself
   * @throws IOException if any IO error occurs
   */
  private void allergy(String personID, String encounterID,
      Entry allergy) throws IOException {
    // START,STOP,PATIENT,ENCOUNTER,CODE,DESCRIPTION
    StringBuilder s = new StringBuilder();

    s.append(dateFromTimestamp(allergy.start)).append(',');
    if (allergy.stop != 0L) {
      s.append(dateFromTimestamp(allergy.stop));
    }
    s.append(',');
    s.append(personID).append(',');
    s.append(encounterID).append(',');

    Code coding = allergy.codes.get(0);

    s.append(coding.code).append(',');
    s.append(clean(coding.display));

    s.append(NEWLINE);
    write(s.toString(), allergies);
  }

  /**
   * Write a single Observation to observations.csv.
   *
   * @param personID ID of the person to whom the observation applies.
   * @param encounterID ID of the encounter where the observation was taken
   * @param observation The observation itself
   * @throws IOException if any IO error occurs
   */
  private void observation(String personID, String encounterID,
      Observation observation) throws IOException {

    if (observation.value == null) {
      if (observation.observations != null && !observation.observations.isEmpty()) {
        // just loop through the child observations

        for (Observation subObs : observation.observations) {
          observation(personID, encounterID, subObs);
        }
      }

      // no value so nothing more to report here
      return;
    }

    // DATE,PATIENT,ENCOUNTER,CODE,DESCRIPTION,VALUE,UNITS
    StringBuilder s = new StringBuilder();

    s.append(dateFromTimestamp(observation.start)).append(',');
    s.append(personID).append(',');
    s.append(encounterID).append(',');

    Code coding = observation.codes.get(0);

    s.append(coding.code).append(',');
    s.append(clean(coding.display)).append(',');

    String value = ExportHelper.getObservationValue(observation);
    String type = ExportHelper.getObservationType(observation);
    s.append(value).append(',');
    s.append(observation.unit).append(',');
    s.append(type);

    s.append(NEWLINE);
    write(s.toString(), observations);
  }

  /**
   * Write a single Procedure to procedures.csv.
   *
   * @param personID ID of the person on whom the procedure was performed.
   * @param encounterID ID of the encounter where the procedure was performed
   * @param procedure The procedure itself
   * @throws IOException if any IO error occurs
   */
  private void procedure(String personID, String encounterID,
      Procedure procedure) throws IOException {
    // DATE,PATIENT,ENCOUNTER,CODE,DESCRIPTION,COST,REASONCODE,REASONDESCRIPTION
    StringBuilder s = new StringBuilder();

    s.append(dateFromTimestamp(procedure.start)).append(',');
    s.append(personID).append(',');
    s.append(encounterID).append(',');

    Code coding = procedure.codes.get(0);

    s.append(coding.code).append(',');
    s.append(clean(coding.display)).append(',');

    s.append(String.format(Locale.US, "%.2f", procedure.cost())).append(',');

    if (procedure.reasons.isEmpty()) {
      s.append(','); // reason code & desc
    } else {
      Code reason = procedure.reasons.get(0);
      s.append(reason.code).append(',');
      s.append(clean(reason.display));
    }

    s.append(NEWLINE);
    write(s.toString(), procedures);
  }

  /**
   * Write a single Medication to medications.csv.
   *
   * @param personID ID of the person prescribed the medication.
   * @param encounterID ID of the encounter where the medication was prescribed
   * @param medication The medication itself
   * @param stopTime End time
   * @throws IOException if any IO error occurs
   */
  private void medication(String personID, String encounterID,
      Medication medication, long stopTime) throws IOException {
    // START,STOP,PATIENT,ENCOUNTER,CODE,DESCRIPTION,
    // COST,DISPENSES,TOTALCOST,REASONCODE,REASONDESCRIPTION
    StringBuilder s = new StringBuilder();

    s.append(dateFromTimestamp(medication.start)).append(',');
    if (medication.stop != 0L) {
      s.append(dateFromTimestamp(medication.stop));
    }
    s.append(',');
    s.append(personID).append(',');
    s.append(encounterID).append(',');

    Code coding = medication.codes.get(0);

    s.append(coding.code).append(',');
    s.append(clean(coding.display)).append(',');

    BigDecimal cost = medication.cost();
    s.append(String.format(Locale.US, "%.2f", cost)).append(',');
    long dispenses = 1; // dispenses = refills + original
    // makes the math cleaner and more explicit. dispenses * unit cost = total cost
    
    long stop = medication.stop;
    if (stop == 0L) {
      stop = stopTime;
    }
    long medDuration = stop - medication.start;

    if (medication.prescriptionDetails != null 
        && medication.prescriptionDetails.has("refills")) {
      dispenses = medication.prescriptionDetails.get("refills").getAsInt();
    } else if (medication.prescriptionDetails != null 
        && medication.prescriptionDetails.has("duration")) {
      JsonObject duration = medication.prescriptionDetails.getAsJsonObject("duration");
      
      long quantity = duration.get("quantity").getAsLong();
      String unit = duration.get("unit").getAsString();
      long durationMs = Utilities.convertTime(unit, quantity);
      dispenses = medDuration / durationMs;
    } else {
      // assume 1 refill / month
      long durationMs = Utilities.convertTime("months", 1);
      dispenses = medDuration / durationMs;
    }
    
    if (dispenses < 1) {
      // integer division could leave us with 0, 
      // ex. if the active time (start->stop) is less than the provided duration
      // or less than a month if no duration provided
      dispenses = 1;
    }

    s.append(dispenses).append(','); 
    BigDecimal totalCost = cost
        .multiply(BigDecimal.valueOf(dispenses))
        .setScale(2, RoundingMode.DOWN); // truncate to 2 decimal places
    s.append(String.format(Locale.US, "%.2f", totalCost)).append(',');

    if (medication.reasons.isEmpty()) {
      s.append(','); // reason code & desc
    } else {
      Code reason = medication.reasons.get(0);
      s.append(reason.code).append(',');
      s.append(clean(reason.display));
    }

    s.append(NEWLINE);
    write(s.toString(), medications);
  }

  /**
   * Write a single Immunization to immunizations.csv.
   *
   * @param personID ID of the person on whom the immunization was performed.
   * @param encounterID ID of the encounter where the immunization was performed
   * @param immunization The immunization itself
   * @throws IOException if any IO error occurs
   */
  private void immunization(String personID, String encounterID,
      Entry immunization) throws IOException  {
    // DATE,PATIENT,ENCOUNTER,CODE,DESCRIPTION,COST
    StringBuilder s = new StringBuilder();

    s.append(dateFromTimestamp(immunization.start)).append(',');
    s.append(personID).append(',');
    s.append(encounterID).append(',');

    Code coding = immunization.codes.get(0);

    s.append(coding.code).append(',');
    s.append(clean(coding.display)).append(',');

    s.append(String.format(Locale.US, "%.2f", immunization.cost()));

    s.append(NEWLINE);
    write(s.toString(), immunizations);
  }

  /**
   * Write a single CarePlan to careplans.csv.
   *
   * @param personID ID of the person prescribed the careplan.
   * @param encounterID ID of the encounter where the careplan was prescribed
   * @param careplan The careplan itself
   * @throws IOException if any IO error occurs
   */
  private String careplan(String personID, String encounterID,
      CarePlan careplan) throws IOException {
    // ID,START,STOP,PATIENT,ENCOUNTER,CODE,DESCRIPTION,REASONCODE,REASONDESCRIPTION
    StringBuilder s = new StringBuilder();

    String careplanID = UUID.randomUUID().toString();
    s.append(careplanID).append(',');
    s.append(dateFromTimestamp(careplan.start)).append(',');
    if (careplan.stop != 0L) {
      s.append(dateFromTimestamp(careplan.stop));
    }
    s.append(',');
    s.append(personID).append(',');
    s.append(encounterID).append(',');

    Code coding = careplan.codes.get(0);

    s.append(coding.code).append(',');
    s.append(coding.display).append(',');

    if (careplan.reasons.isEmpty()) {
      s.append(','); // reason code & desc
    } else {
      Code reason = careplan.reasons.get(0);
      s.append(reason.code).append(',');
      s.append(clean(reason.display));
    }
    s.append(NEWLINE);

    write(s.toString(), careplans);

    return careplanID;
  }

  /**
   * Write a single ImagingStudy to imaging_studies.csv.
   *
   * @param personID ID of the person the ImagingStudy was taken of.
   * @param encounterID ID of the encounter where the ImagingStudy was performed
   * @param imagingStudy The ImagingStudy itself
   * @throws IOException if any IO error occurs
   */
  private String imagingStudy(String personID, String encounterID,
      ImagingStudy imagingStudy) throws IOException {
    // ID,DATE,PATIENT,ENCOUNTER,BODYSITE_CODE,BODYSITE_DESCRIPTION,
    // MODALITY_CODE,MODALITY_DESCRIPTION,SOP_CODE,SOP_DESCRIPTION
    StringBuilder s = new StringBuilder();

    String studyID = UUID.randomUUID().toString();
    s.append(studyID).append(',');
    s.append(dateFromTimestamp(imagingStudy.start)).append(',');
    s.append(personID).append(',');
    s.append(encounterID).append(',');

    ImagingStudy.Series series1 = imagingStudy.series.get(0);
    ImagingStudy.Instance instance1 = series1.instances.get(0);

    Code bodySite = series1.bodySite;
    Code modality = series1.modality;
    Code sopClass = instance1.sopClass;

    s.append(bodySite.code).append(',');
    s.append(bodySite.display).append(',');

    s.append(modality.code).append(',');
    s.append(modality.display).append(',');

    s.append(sopClass.code).append(',');
    s.append(sopClass.display);

    s.append(NEWLINE);

    write(s.toString(), imagingStudies);

    return studyID;
  }

  /**
   * Replaces commas and line breaks in the source string with a single space.
   * Null is replaced with the empty string.
   */
  private static String clean(String src) {
    if (src == null) {
      return "";
    } else {
      return src.replaceAll("\\r\\n|\\r|\\n|,", " ").trim();
    }
  }

  /**
   * Helper method to write a line to a File.
   * Extracted to a separate method here to make it a little easier to replace implementations.
   *
   * @param line The line to write
   * @param writer The place to write it
   * @throws IOException if an I/O error occurs
   */
  private static void write(String line, FileWriter writer) throws IOException {
    synchronized (writer) {
      writer.write(line);
    }
  }
}
