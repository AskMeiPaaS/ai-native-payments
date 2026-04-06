import { useState, useEffect, useCallback } from 'react';

export interface TelemetryVector {
  averageFlightTimeMs: number;
  averageDwellTimeMs: number;
  deviceHash: string;
}

/**
 * Captures keystroke dynamics and device telemetry without impacting UX.
 * This data is sent to the Java Context Agent for continuous implicit authentication.
 */
export function useTelemetry() {
  const [keyPresses, setKeyPresses] = useState<{ key: string; down: number; up: number }[]>([]);
  const [currentKeyDown, setCurrentKeyDown] = useState<{ [key: string]: number }>({});

  const handleKeyDown = useCallback((e: KeyboardEvent) => {
    setCurrentKeyDown((prev) => ({ ...prev, [e.key]: performance.now() }));
  }, []);

  const handleKeyUp = useCallback((e: KeyboardEvent) => {
    const downTime = currentKeyDown[e.key];
    if (downTime) {
      const upTime = performance.now();
      setKeyPresses((prev) => [...prev, { key: e.key, down: downTime, up: upTime }]);
      
      // Clean up to prevent memory leaks
      setCurrentKeyDown((prev) => {
        const next = { ...prev };
        delete next[e.key];
        return next;
      });
    }
  }, [currentKeyDown]);

  useEffect(() => {
    window.addEventListener('keydown', handleKeyDown);
    window.addEventListener('keyup', handleKeyUp);
    return () => {
      window.removeEventListener('keydown', handleKeyDown);
      window.removeEventListener('keyup', handleKeyUp);
    };
  }, [handleKeyDown, handleKeyUp]);

  // Compress raw events into a structured vector for the Voyage AI embedding
  const getTelemetryVector = (): TelemetryVector => {
    if (keyPresses.length === 0) return { averageFlightTimeMs: 0, averageDwellTimeMs: 0, deviceHash: navigator.userAgent };

    const dwellTimes = keyPresses.map(k => k.up - k.down);
    const avgDwell = dwellTimes.reduce((a, b) => a + b, 0) / dwellTimes.length;

    let flightTimes: number[] = [];
    for (let i = 1; i < keyPresses.length; i++) {
      flightTimes.push(keyPresses[i].down - keyPresses[i-1].up);
    }
    const avgFlight = flightTimes.length > 0 ? flightTimes.reduce((a, b) => a + b, 0) / flightTimes.length : 0;

    return {
      averageDwellTimeMs: Math.round(avgDwell),
      averageFlightTimeMs: Math.round(avgFlight),
      deviceHash: btoa(navigator.userAgent).substring(0, 32) // Basic pseudonymization
    };
  };

  return { getTelemetryVector };
}