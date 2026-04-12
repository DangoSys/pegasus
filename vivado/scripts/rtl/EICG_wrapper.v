// EICG_wrapper: simulation clock gate used by Chipyard/DigitalTop.
// FPGA implementation: latch-free AND gate (Vivado handles clock enables separately).
module EICG_wrapper(
  output out,
  input  en,
  input  test_en,
  input  in
);
  assign out = in & (en | test_en);
endmodule
