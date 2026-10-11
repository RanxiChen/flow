open_checkpoint /home/chen/FUN/flow-runs/frontend-pipeline-20261010/route-03/place.dcp
set tiles [get_tiles -filter {TYPE =~ INT*}]
puts "INT_COUNT=[llength $tiles] FIRST=[lrange $tiles 0 3]"
report_property [lindex $tiles 0]
set ends [all_fanout -flat -endpoints_only -from [get_ports io_context_pmpRangesValid]]
set cell [lindex [get_cells -of_objects $ends] 0]
set site [get_sites [get_property LOC $cell]]
puts "CELL=$cell SITE=$site TILE=[get_tiles -of_objects $site]"
report_property [get_tiles -of_objects $site]
