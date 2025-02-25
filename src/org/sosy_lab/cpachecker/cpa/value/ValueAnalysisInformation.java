/*
 * CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2015  Dirk Beyer
 *  All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 *  CPAchecker web page:
 *    http://cpachecker.sosy-lab.org
 */
package org.sosy_lab.cpachecker.cpa.value;

import java.util.Objects;
import org.sosy_lab.common.collect.PathCopyingPersistentTreeMap;
import org.sosy_lab.common.collect.PersistentMap;
import org.sosy_lab.cpachecker.cpa.value.ValueAnalysisState.ValueAndType;
import org.sosy_lab.cpachecker.util.states.MemoryLocation;

/**
 * Information about value assignments needed for symbolic interpolation.
 */
public class ValueAnalysisInformation {

  public static final ValueAnalysisInformation EMPTY = new ValueAnalysisInformation();

  private final PersistentMap<MemoryLocation, ValueAndType> assignments;

  protected ValueAnalysisInformation(
      final PersistentMap<MemoryLocation, ValueAndType> pAssignments) {
    assignments = pAssignments;
  }

  private ValueAnalysisInformation() {
    assignments = PathCopyingPersistentTreeMap.of();
  }

  public PersistentMap<MemoryLocation, ValueAndType> getAssignments() {
    return assignments;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    ValueAnalysisInformation that = (ValueAnalysisInformation) o;
    return assignments.equals(that.assignments);
  }

  @Override
  public int hashCode() {
    return Objects.hash(assignments);
  }

  @Override
  public String toString() {
    return "ValueInformation[" + assignments + "]";
  }
}
