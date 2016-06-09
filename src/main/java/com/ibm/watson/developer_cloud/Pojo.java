/**
 * Copyright 2015 IBM Corp. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.ibm.watson.developer_cloud;

import java.util.Arrays;
import java.util.List;

/**
 * The class Pojo.
 */
public class Pojo {

  /**
   * No operation.
   */
  private void noop() {

  }

  /**
   * A method with two branches.
   * @param unit unit
   */
  public void twoBranches(boolean unit) {
    if(unit) {
      noop();
    } else {
      noop();
    }
  }

  /**
   * A method with a loop.
   */
  public void completeLoop() {
    for (int i = 0; i < 5; i++) {
      noop();
    }
  }

  /**
   * A method with a foreach loop.
   */
  public void completeLoop2() {
    List<Integer> list = Arrays.asList(1,2,3);
    for(Integer i : list) {
      noop();
    }
  }

}
