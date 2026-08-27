package io.github.aililuola.mathproofmesh.computation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.EvidenceStrength;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Exact finite-dimensional linear algebra over arbitrary-precision rationals. */
public final class ExactLinearAlgebraFunctions {
  private ExactLinearAlgebraFunctions() {}

  public static HandlerEvidence run(ExperimentSpec spec) {
    ObjectNode arguments = spec.arguments();
    String operation = requiredText(arguments, "operation");
    ExactRational[][] matrix = matrix(arguments.get("matrix"), "matrix");
    Reduction reduction = reduce(matrix);
    ObjectNode certificate = ComputationJson.object();
    certificate.put("operation", operation);
    certificate.set("matrix", matrixNode(matrix));
    certificate.set("rref", matrixNode(reduction.rref()));
    certificate.set("pivot_columns", integers(reduction.pivotColumns()));
    certificate.put(
        "row_operation_digest",
        CanonicalJson.stableHash(matrixNode(reduction.rref())));

    switch (operation) {
      case "determinant" -> {
        if (matrix.length != columns(matrix)) {
          throw new IllegalArgumentException("determinant requires a square matrix");
        }
        certificate.put("determinant", determinant(matrix).toString());
      }
      case "rank" -> certificate.put("rank", reduction.pivotColumns().size());
      case "solve" -> solve(matrix, arguments, certificate);
      case "nullspace" -> {
        List<ExactRational[]> basis = nullspace(reduction);
        certificate.set("nullspace_basis", vectors(basis));
        certificate.put("nullity", basis.size());
        certificate.put("a_times_basis_is_zero", allNullVectorsValid(matrix, basis));
      }
      case "span_membership" -> spanMembership(matrix, arguments, certificate);
      default ->
          throw new IllegalArgumentException(
              "unsupported exact linear algebra operation: " + operation);
    }

    ObjectNode scope =
        ComputationJson.object()
            .put("complete_domain", true)
            .put("field", "Q")
            .put("rows", matrix.length)
            .put("columns", columns(matrix))
            .put("operation", operation);
    return new HandlerEvidence(
        ExperimentOutcome.CERTIFIED,
        EvidenceStrength.EXHAUSTIVE_CERTIFICATE,
        scope,
        null,
        certificate,
        true,
        Math.max(1, matrix.length * Math.max(1, columns(matrix))),
        false,
        List.of("Exact rational certificate requires independent identity verification."),
        null);
  }

  static ExactRational[][] matrix(JsonNode raw, String label) {
    ArrayNode rows = ComputationJson.requiredArray(raw, label);
    if (rows.isEmpty()) {
      throw new IllegalArgumentException(label + " must be nonempty");
    }
    int width = -1;
    ExactRational[][] result = new ExactRational[rows.size()][];
    for (int row = 0; row < rows.size(); row++) {
      JsonNode rawRow = rows.get(row);
      if (!rawRow.isArray() || rawRow.isEmpty()) {
        throw new IllegalArgumentException(label + " rows must be nonempty arrays");
      }
      if (width < 0) {
        width = rawRow.size();
      } else if (rawRow.size() != width) {
        throw new IllegalArgumentException(label + " must be rectangular");
      }
      result[row] = new ExactRational[width];
      for (int column = 0; column < width; column++) {
        result[row][column] =
            ExactRational.parse(rawRow.get(column), label + '[' + row + "][" + column + ']');
      }
    }
    return result;
  }

  static ExactRational[] vector(JsonNode raw, String label) {
    ArrayNode values = ComputationJson.requiredArray(raw, label);
    ExactRational[] result = new ExactRational[values.size()];
    for (int index = 0; index < values.size(); index++) {
      result[index] = ExactRational.parse(values.get(index), label + '[' + index + ']');
    }
    return result;
  }

  static Reduction reduce(ExactRational[][] source) {
    ExactRational[][] values = copy(source);
    List<Integer> pivots = new ArrayList<>();
    int row = 0;
    for (int column = 0; column < columns(values) && row < values.length; column++) {
      int pivot = row;
      while (pivot < values.length && values[pivot][column].signum() == 0) {
        pivot++;
      }
      if (pivot == values.length) {
        continue;
      }
      ExactRational[] swap = values[row];
      values[row] = values[pivot];
      values[pivot] = swap;
      ExactRational scale = values[row][column];
      for (int index = 0; index < columns(values); index++) {
        values[row][index] = values[row][index].divide(scale);
      }
      for (int other = 0; other < values.length; other++) {
        if (other == row || values[other][column].signum() == 0) {
          continue;
        }
        ExactRational factor = values[other][column];
        for (int index = 0; index < columns(values); index++) {
          values[other][index] =
              values[other][index].subtract(factor.multiply(values[row][index]));
        }
      }
      pivots.add(column);
      row++;
    }
    return new Reduction(values, List.copyOf(pivots));
  }

  static ExactRational determinant(ExactRational[][] source) {
    int size = source.length;
    if (size != columns(source)) {
      throw new IllegalArgumentException("determinant requires a square matrix");
    }
    ExactRational[][] values = copy(source);
    ExactRational determinant = ExactRational.ONE;
    for (int column = 0; column < size; column++) {
      int pivot = column;
      while (pivot < size && values[pivot][column].signum() == 0) {
        pivot++;
      }
      if (pivot == size) {
        return ExactRational.ZERO;
      }
      if (pivot != column) {
        ExactRational[] swap = values[column];
        values[column] = values[pivot];
        values[pivot] = swap;
        determinant = determinant.negate();
      }
      ExactRational pivotValue = values[column][column];
      determinant = determinant.multiply(pivotValue);
      for (int row = column + 1; row < size; row++) {
        if (values[row][column].signum() == 0) {
          continue;
        }
        ExactRational factor = values[row][column].divide(pivotValue);
        for (int index = column; index < size; index++) {
          values[row][index] =
              values[row][index].subtract(factor.multiply(values[column][index]));
        }
      }
    }
    return determinant;
  }

  static List<ExactRational[]> nullspace(Reduction reduction) {
    int width = columns(reduction.rref());
    java.util.Set<Integer> pivotSet = java.util.Set.copyOf(reduction.pivotColumns());
    List<ExactRational[]> basis = new ArrayList<>();
    for (int free = 0; free < width; free++) {
      if (pivotSet.contains(free)) {
        continue;
      }
      ExactRational[] vector = zeros(width);
      vector[free] = ExactRational.ONE;
      for (int row = 0; row < reduction.pivotColumns().size(); row++) {
        int pivot = reduction.pivotColumns().get(row);
        vector[pivot] = reduction.rref()[row][free].negate();
      }
      basis.add(vector);
    }
    return List.copyOf(basis);
  }

  static ExactRational[] multiply(ExactRational[][] matrix, ExactRational[] vector) {
    if (columns(matrix) != vector.length) {
      throw new IllegalArgumentException("matrix and vector dimensions do not agree");
    }
    ExactRational[] result = zeros(matrix.length);
    for (int row = 0; row < matrix.length; row++) {
      for (int column = 0; column < vector.length; column++) {
        result[row] = result[row].add(matrix[row][column].multiply(vector[column]));
      }
    }
    return result;
  }

  private static void solve(
      ExactRational[][] matrix, ObjectNode arguments, ObjectNode certificate) {
    ExactRational[] rhs = vector(arguments.get("rhs"), "rhs");
    if (rhs.length != matrix.length) {
      throw new IllegalArgumentException("rhs length must equal the matrix row count");
    }
    ExactRational[][] augmented = augment(matrix, rhs);
    Reduction reduction = reduce(augmented);
    int variableCount = columns(matrix);
    boolean consistent = true;
    for (ExactRational[] row : reduction.rref()) {
      boolean zeroLeft = true;
      for (int column = 0; column < variableCount; column++) {
        zeroLeft &= row[column].signum() == 0;
      }
      if (zeroLeft && row[variableCount].signum() != 0) {
        consistent = false;
      }
    }
    certificate.put("consistent", consistent);
    certificate.set("rhs", vectorNode(rhs));
    if (!consistent) {
      certificate.put("unique", false);
      return;
    }
    Reduction coefficients = reduce(matrix);
    boolean unique = coefficients.pivotColumns().size() == variableCount;
    certificate.put("unique", unique);
    if (unique) {
      ExactRational[] solution = zeros(variableCount);
      for (int row = 0; row < reduction.pivotColumns().size(); row++) {
        int pivot = reduction.pivotColumns().get(row);
        if (pivot < variableCount) {
          solution[pivot] = reduction.rref()[row][variableCount];
        }
      }
      certificate.set("solution", vectorNode(solution));
      certificate.set("solution_residual", vectorNode(subtract(multiply(matrix, solution), rhs)));
    } else {
      certificate.set("nullspace_basis", vectors(nullspace(coefficients)));
    }
  }

  private static void spanMembership(
      ExactRational[][] matrix, ObjectNode arguments, ObjectNode certificate) {
    ExactRational[] target = vector(arguments.get("vector"), "vector");
    if (target.length != matrix.length) {
      throw new IllegalArgumentException("vector length must equal the matrix row count");
    }
    int rank = reduce(matrix).pivotColumns().size();
    Reduction augmented = reduce(augment(matrix, target));
    int augmentedRank =
        (int)
            augmented.pivotColumns().stream()
                .filter(value -> value < columns(matrix) + 1)
                .count();
    boolean member = rank == augmentedRank;
    certificate.put("member", member);
    certificate.put("rank", rank);
    certificate.put("augmented_rank", augmentedRank);
    certificate.set("vector", vectorNode(target));
    if (member) {
      ExactRational[] coefficients = zeros(columns(matrix));
      for (int row = 0; row < augmented.pivotColumns().size(); row++) {
        int pivot = augmented.pivotColumns().get(row);
        if (pivot < columns(matrix)) {
          coefficients[pivot] = augmented.rref()[row][columns(matrix)];
        }
      }
      certificate.set("coefficients", vectorNode(coefficients));
      certificate.set(
          "solution_residual", vectorNode(subtract(multiply(matrix, coefficients), target)));
    }
  }

  private static boolean allNullVectorsValid(
      ExactRational[][] matrix, List<ExactRational[]> basis) {
    return basis.stream()
        .map(vector -> multiply(matrix, vector))
        .flatMap(values -> java.util.Arrays.stream(values))
        .allMatch(value -> value.signum() == 0);
  }

  private static ExactRational[][] augment(
      ExactRational[][] matrix, ExactRational[] column) {
    ExactRational[][] result = new ExactRational[matrix.length][columns(matrix) + 1];
    for (int row = 0; row < matrix.length; row++) {
      System.arraycopy(matrix[row], 0, result[row], 0, columns(matrix));
      result[row][columns(matrix)] = column[row];
    }
    return result;
  }

  private static ExactRational[] subtract(
      ExactRational[] left, ExactRational[] right) {
    ExactRational[] result = new ExactRational[left.length];
    for (int index = 0; index < left.length; index++) {
      result[index] = left[index].subtract(right[index]);
    }
    return result;
  }

  private static ExactRational[] zeros(int size) {
    ExactRational[] values = new ExactRational[size];
    java.util.Arrays.fill(values, ExactRational.ZERO);
    return values;
  }

  private static ExactRational[][] copy(ExactRational[][] source) {
    ExactRational[][] result = new ExactRational[source.length][];
    for (int row = 0; row < source.length; row++) {
      result[row] = source[row].clone();
    }
    return result;
  }

  private static int columns(ExactRational[][] values) {
    return values.length == 0 ? 0 : values[0].length;
  }

  private static ArrayNode matrixNode(ExactRational[][] values) {
    ArrayNode result = ComputationJson.array();
    for (ExactRational[] row : values) {
      result.add(vectorNode(row));
    }
    return result;
  }

  private static ArrayNode vectors(List<ExactRational[]> values) {
    ArrayNode result = ComputationJson.array();
    values.forEach(value -> result.add(vectorNode(value)));
    return result;
  }

  private static ArrayNode vectorNode(ExactRational[] values) {
    ArrayNode result = ComputationJson.array();
    for (ExactRational value : values) {
      result.add(value.toString());
    }
    return result;
  }

  private static ArrayNode integers(List<Integer> values) {
    ArrayNode result = ComputationJson.array();
    values.forEach(result::add);
    return result;
  }

  private static String requiredText(ObjectNode value, String field) {
    String result = value.path(field).asText("").strip();
    if (result.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return result;
  }

  record Reduction(ExactRational[][] rref, List<Integer> pivotColumns) {
    Reduction {
      rref = copy(rref);
      pivotColumns = List.copyOf(pivotColumns);
    }

    @Override
    public ExactRational[][] rref() {
      return copy(rref);
    }

    @Override
    public List<Integer> pivotColumns() {
      return List.copyOf(pivotColumns);
    }
  }
}
